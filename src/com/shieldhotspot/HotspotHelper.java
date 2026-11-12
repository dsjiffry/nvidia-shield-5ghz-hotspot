package com.shieldhotspot;

import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs as the ADB shell user via:
 *   CLASSPATH=<apk> app_process /system/bin com.shieldhotspot.HotspotHelper <cmd> ...
 * The shell user holds TETHER_PRIVILEGED and NETWORK_SETTINGS, so it can drive the system
 * Tethering service (real NAT + DHCP to the Ethernet upstream).
 *
 * Commands:
 *   watchp2p <name> <pass>              5GHz mode: own a Wi-Fi Direct group and keep it *tethered*
 *                                       (Android starts it local-only; we re-tether it to Ethernet)
 *   watch <ssid> <pass> <band>          2.4GHz mode: keep a standard soft-AP hotspot tethered
 *   stopall                             remove the group and stop soft-AP tethering
 *   up <ssid> <pass> <band> | start | stop | status
 *   tether <iface> | untether <iface>
 */
public final class HotspotHelper {
    static final String PKG = "com.android.shell";
    static final int TETHERING_WIFI = 0;
    static final int SECURITY_WPA2_PSK = 1;
    static final Pattern STATE_LINE = Pattern.compile("^\\s+(\\S+) - (\\w+)State - lastError = (\\d+)");

    public static void main(String[] args) {
        int code = 0;
        try {
            String cmd = args.length > 0 ? args[0] : "";
            switch (cmd) {
                case "config": setConfig(args[1], args[2], Integer.parseInt(args[3])); break;
                case "start": code = report("start", startSoftAp()); break;
                case "stop": code = report("stop", stopSoftAp()); break;
                case "up":
                    setConfig(args[1], args[2], Integer.parseInt(args[3]));
                    code = report("start", startSoftAp());
                    break;
                case "tether": code = report("tether", ifaceCall("tether", args[1])); break;
                case "untether": code = report("untether", ifaceCall("untether", args[1])); break;
                case "status": System.out.println(tetherStates()); break;
                case "watch": watchSoftAp(args[1], args[2], Integer.parseInt(args[3])); break;
                case "watchp2p": watchP2p(args[1], args[2]); break;
                case "stopall": stopAll(); break;
                default:
                    System.out.println("usage: watchp2p|watch|stopall|up|config|start|stop|status|tether|untether");
                    code = 2;
            }
        } catch (Throwable t) {
            System.out.println("ERROR " + unwrap(t));
            code = 1;
        }
        System.out.flush();
        System.exit(code);
    }

    // ---------- watchdogs ----------

    /** 5GHz mode: the app owns a Wi-Fi Direct group on 5GHz; we make Android tether it to Ethernet. */
    /** Single-instance lock: an abstract-namespace socket exists only while its owner is alive. */
    static Object singleInstance() {
        try {
            return new android.net.LocalServerSocket("shieldhotspot_watch_lock");
        } catch (java.io.IOException e) {
            log("another watchdog is already running, exiting");
            System.exit(0);
            return null;
        }
    }

    static Object lock;
    static final String STATUS = "/data/local/tmp/shieldhotspot.status";
    static final Pattern UPSTREAM = Pattern.compile("Current upstream interface\\(s\\): \\[?([^\\]]*)\\]?");

    /**
     * 5GHz mode: this process owns a Wi-Fi Direct group on 5GHz (P2pOwner, main looper) and a
     * background loop switches it from Android's "local only" mode to real tethering (NAT + DHCP).
     */
    static void watchP2p(String ssid, String pass) throws Exception {
        lock = singleInstance();
        log("watchp2p starting name=" + P2pOwner.TAG_PREFIX + ssid);
        Looper.prepareMainLooper();
        final P2pOwner owner = new P2pOwner(ssid, pass);
        owner.start();
        Thread t = new Thread(() -> tetherLoop(owner), "tether-loop");
        t.setDaemon(true);
        t.start();
        Looper.loop();
    }

    static void tetherLoop(P2pOwner owner) {
        String last = "";
        while (true) {
            try {
                Map<String, String> st = tetherStates();
                for (Map.Entry<String, String> e : st.entrySet()) {
                    String iface = e.getKey(), s = e.getValue();
                    if (!iface.startsWith("p2p")) continue;
                    if (s.equals("LocalHotspot")) {
                        log(iface + " is local-only, switching to tethered");
                        log("  untether=" + ifaceCall("untether", iface));
                        Thread.sleep(500);
                        log("  tether=" + ifaceCall("tether", iface));
                    } else if (s.equals("Available")) {
                        log(iface + " available, tether=" + ifaceCall("tether", iface));
                    }
                }
                st = tetherStates();
                String tether = owner.iface.isEmpty() ? "" : String.valueOf(st.get(owner.iface));
                writeStatus("5ghz", owner.networkName, owner.groupUp && "Tethered".equals(tether),
                        owner.frequency, owner.clients, tether, st.get("_upstream"));
                String now = st.toString();
                if (!now.equals(last)) { log("state " + now); last = now; }
            } catch (Throwable t) {
                log("watch error " + unwrap(t));
            }
            try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
        }
    }

    /** 2.4GHz mode: keep the standard soft-AP hotspot up. */
    static void watchSoftAp(String ssid, String pass, int band) throws Exception {
        lock = singleInstance();
        log("watch starting ssid=" + ssid + " band=" + band);
        setConfig(ssid, pass, band);
        while (true) {
            try {
                Map<String, String> st = tetherStates();
                if (!"Tethered".equals(st.get("wlan0"))) {
                    log("hotspot was down, start result=" + startSoftAp());
                    st = tetherStates();
                }
                writeStatus("2.4ghz", ssid, "Tethered".equals(st.get("wlan0")), 0, -1,
                        String.valueOf(st.get("wlan0")), st.get("_upstream"));
            } catch (Throwable t) {
                log("watch error " + unwrap(t));
            }
            Thread.sleep(10000);
        }
    }

    /** Stops everything: Wi-Fi Direct group and soft-AP tethering. */
    static void stopAll() throws Exception {
        log("stop soft-AP result=" + stopSoftAp());
        Looper.prepareMainLooper();
        new P2pOwner("", "").removeExisting();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            writeStatus("off", "", false, 0, -1, "", "");
            System.exit(0);
        }, 3000);
        Looper.loop();
    }

    /** Small key=value file the app reads (via adb) to show live status. Written atomically. */
    static void writeStatus(String mode, String name, boolean up, int freq, int clients, String tether, String upstream) {
        try {
            String s = "mode=" + mode + "\nname=" + name + "\nup=" + up + "\nfreq=" + freq + "\nclients=" + clients
                    + "\ntether=" + tether + "\nupstream=" + (upstream == null ? "" : upstream)
                    + "\ntime=" + System.currentTimeMillis() + "\n";
            java.io.File tmp = new java.io.File(STATUS + ".tmp");
            try (java.io.FileOutputStream f = new java.io.FileOutputStream(tmp)) { f.write(s.getBytes("UTF-8")); }
            tmp.setReadable(true, false);
            tmp.renameTo(new java.io.File(STATUS));
        } catch (Exception e) {
            log("status write failed " + e);
        }
    }

    /** Parses "iface - XxxState - lastError = N" lines (and the upstream, as "_upstream") from dumpsys tethering. */
    static Map<String, String> tetherStates() throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"dumpsys", "tethering"});
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        Map<String, String> m = new LinkedHashMap<>();
        String line;
        while ((line = r.readLine()) != null) {
            if (line.startsWith("Log:")) break;
            Matcher x = STATE_LINE.matcher(line);
            if (x.find()) { m.put(x.group(1), x.group(2)); continue; }
            Matcher u = UPSTREAM.matcher(line);
            if (u.find() && !m.containsKey("_upstream")) m.put("_upstream", u.group(1).replace("null", "").trim());
        }
        p.waitFor();
        return m;
    }

    // ---------- system service calls ----------

    static IBinder service(String name) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, name);
        if (b == null) throw new IllegalStateException("service not found: " + name);
        return b;
    }

    static Object asInterface(String iface, IBinder b) throws Exception {
        return Class.forName(iface + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, b);
    }

    static Method find(Class<?> c, String name) {
        for (Method m : c.getMethods()) if (m.getName().equals(name)) return m;
        throw new IllegalStateException("method not found: " + c.getName() + "." + name);
    }

    static void setConfig(String ssid, String pass, int band) throws Exception {
        Class<?> bc = Class.forName("android.net.wifi.SoftApConfiguration$Builder");
        Object b = bc.getConstructor().newInstance();
        bc.getMethod("setSsid", String.class).invoke(b, ssid);
        bc.getMethod("setPassphrase", String.class, int.class).invoke(b, pass, SECURITY_WPA2_PSK);
        int channel = Integer.parseInt(System.getProperty("ch", "0"));
        if (channel > 0) bc.getMethod("setChannel", int.class, int.class).invoke(b, channel, channel > 14 ? 2 : 1);
        else bc.getMethod("setBand", int.class).invoke(b, band);
        try { bc.getMethod("setAutoShutdownEnabled", boolean.class).invoke(b, false); } catch (NoSuchMethodException ignored) { }
        Object cfg = bc.getMethod("build").invoke(b);
        if (channel > 0) {
            // Builder only allows a single band with a pinned channel; force the requested band mask.
            java.lang.reflect.Field f = cfg.getClass().getDeclaredField("mBand");
            f.setAccessible(true);
            f.setInt(cfg, band);
        }
        Object wifi = asInterface("android.net.wifi.IWifiManager", service("wifi"));
        Object r = find(wifi.getClass(), "setSoftApConfiguration").invoke(wifi, cfg, PKG);
        System.out.println("config ssid=" + ssid + " band=" + band + " saved=" + r);
    }

    /** Result listener for ITetheringConnector calls. */
    static final class Result extends Binder {
        volatile int res = -999;
        final CountDownLatch latch = new CountDownLatch(1);
        Object listener;

        static Result create() throws Exception {
            Result r = new Result();
            r.listener = asInterface("android.net.IIntResultListener", r);
            return r;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == IBinder.FIRST_CALL_TRANSACTION) {
                data.enforceInterface("android.net.IIntResultListener");
                res = data.readInt();
                latch.countDown();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }

        int await() throws InterruptedException {
            latch.await(20, TimeUnit.SECONDS);
            return res;
        }
    }

    static Object connector() throws Exception {
        return asInterface("android.net.ITetheringConnector", service("tethering"));
    }

    /** Calls connector method whose String params are, in order, [first, callerPkg, attributionTag...]. */
    static int call(String method, Object first) throws Exception {
        Object conn = connector();
        Result res = Result.create();
        Method m = find(conn.getClass(), method);
        Class<?>[] types = m.getParameterTypes();
        Object[] a = new Object[types.length];
        int strings = 0;
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (t == int.class) a[i] = first instanceof Integer ? first : TETHERING_WIFI;
            else if (t == String.class) {
                if (first instanceof String) a[i] = strings == 0 ? first : strings == 1 ? PKG : null;
                else a[i] = strings == 0 ? PKG : null;
                strings++;
            } else if (t.getName().equals("android.net.TetheringRequestParcel")) a[i] = first;
            else if (t.getName().equals("android.net.IIntResultListener")) a[i] = res.listener;
        }
        m.invoke(conn, a);
        return res.await();
    }

    static int ifaceCall(String method, String iface) throws Exception {
        return call(method, iface);
    }

    static int startSoftAp() throws Exception {
        Class<?> pc = Class.forName("android.net.TetheringRequestParcel");
        Object request = pc.getConstructor().newInstance();
        pc.getField("tetheringType").setInt(request, TETHERING_WIFI);
        // exemptFromEntitlementCheck=true would need NETWORK_STACK permission (shell lacks it).
        try { pc.getField("showProvisioningUi").setBoolean(request, false); } catch (NoSuchFieldException ignored) { }
        return call("startTethering", request);
    }

    static int stopSoftAp() throws Exception {
        return call("stopTethering", TETHERING_WIFI);
    }

    // ---------- util ----------

    static Throwable unwrap(Throwable t) {
        return t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
    }

    static int report(String what, int r) {
        // TetheringManager.TETHER_ERROR_NO_ERROR == 0
        System.out.println(what + " result=" + r + (r == 0 ? " OK" : r == -999 ? " TIMEOUT" : " FAILED"));
        return r == 0 ? 0 : 1;
    }

    static void log(String s) {
        System.out.println(new java.text.SimpleDateFormat("MM-dd HH:mm:ss").format(new java.util.Date()) + " " + s);
        System.out.flush();
    }
}
