package com.shieldhotspot;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Owns the 5GHz Wi-Fi Direct group from inside the shell-user helper process.
 * The shell user holds NETWORK_SETTINGS, which exempts Wi-Fi Direct calls from the location-permission
 * checks, so the app itself needs no location permission. The group lives as long as this process.
 *
 * Runs on the main looper of the helper. Only static nested classes / lambdas are used
 * (d8 8.2 cannot dex javac 21 inner-class constructors).
 */
final class P2pOwner {
    static final String TAG_PREFIX = "DIRECT-SH-";

    final String ssid, pass, networkName;
    final Handler main;
    final WifiP2pManager p2p;
    final Context ctx = new ShellContext();
    WifiP2pManager.Channel channel;
    boolean creating;
    int busyCount, noGroupChecks;
    volatile int frequency, clients;
    volatile boolean groupUp;
    volatile String iface = "";

    P2pOwner(String ssid, String pass) throws Exception {
        this.ssid = ssid;
        this.pass = pass;
        this.networkName = TAG_PREFIX + ssid;
        this.main = new Handler(Looper.getMainLooper());
        IBinder b = HotspotHelper.service("wifip2p");
        Object svc = HotspotHelper.asInterface("android.net.wifi.p2p.IWifiP2pManager", b);
        Class<?> ifc = Class.forName("android.net.wifi.p2p.IWifiP2pManager");
        p2p = WifiP2pManager.class.getConstructor(ifc).newInstance(svc);
    }

    void start() {
        removeExisting();
        main.postDelayed(new Tick(this), 2000);
    }

    /** Drops any current group (e.g. a stale one owned by an older app version). */
    void removeExisting() {
        ensureChannel();
        if (channel != null) p2p.removeGroup(channel, new Listener(this, "removeOld", null));
    }

    void stop() {
        if (channel != null) p2p.removeGroup(channel, null);
    }

    private void ensureChannel() {
        if (busyCount >= 3 && channel != null) {
            HotspotHelper.log("p2p busy repeatedly, re-initializing channel");
            channel.close();
            channel = null;
            busyCount = 0;
            creating = false;
        }
        if (channel == null) {
            channel = p2p.initialize(ctx, Looper.getMainLooper(), () -> {
                HotspotHelper.log("p2p channel lost");
                channel = null;
            });
        }
    }

    /** Runs every 15s: makes sure our group exists with the right name. */
    void check() {
        ensureChannel();
        if (channel == null || creating) return;
        p2p.requestGroupInfo(channel, g -> onGroupInfo(g));
    }

    private void onGroupInfo(WifiP2pGroup g) {
        if (channel == null) return;
        if (g != null && g.isGroupOwner() && networkName.equals(g.getNetworkName())) {
            noGroupChecks = 0;
            groupUp = true;
            frequency = g.getFrequency();
            clients = g.getClientList().size();
            iface = g.getInterface() == null ? "" : g.getInterface();
            return;
        }
        groupUp = false;
        if (++noGroupChecks >= 8) {
            // Wi-Fi Direct stuck for ~2 minutes (e.g. supplicant lost the P2P interface): restart Wi-Fi.
            noGroupChecks = 0;
            HotspotHelper.log("no Wi-Fi Direct group for ~2 minutes, restarting Wi-Fi");
            new Thread(() -> {
                try {
                    Runtime.getRuntime().exec(new String[]{"sh", "-c", "svc wifi disable; sleep 8; svc wifi enable"}).waitFor();
                } catch (Exception e) {
                    HotspotHelper.log("wifi restart failed " + e);
                }
            }).start();
            return;
        }
        if (g != null) {
            HotspotHelper.log("replacing group " + g.getNetworkName());
            p2p.removeGroup(channel, new Listener(this, "removeGroup", this::createGroup));
        } else {
            createGroup();
        }
    }

    void createGroup() {
        if (channel == null) return;
        creating = true;
        WifiP2pConfig cfg = new WifiP2pConfig.Builder()
                .setNetworkName(networkName)
                .setPassphrase(pass)
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
                .enablePersistentMode(false)
                .build();
        p2p.createGroup(channel, cfg, new Listener(this, "createGroup", null));
    }

    static final class Tick implements Runnable {
        final P2pOwner o;
        Tick(P2pOwner o) { this.o = o; }

        @Override
        public void run() {
            try { o.check(); } catch (Throwable t) { HotspotHelper.log("p2p check error " + HotspotHelper.unwrap(t)); }
            o.main.postDelayed(this, 15000);
        }
    }

    static final class Listener implements WifiP2pManager.ActionListener {
        final P2pOwner o;
        final String what;
        final Runnable then;

        Listener(P2pOwner o, String what, Runnable then) {
            this.o = o;
            this.what = what;
            this.then = then;
        }

        @Override
        public void onSuccess() {
            if (!what.equals("removeOld")) HotspotHelper.log(what + " ok");
            if (what.equals("createGroup")) o.creating = false;
            o.busyCount = 0;
            if (then != null) then.run();
        }

        @Override
        public void onFailure(int reason) {
            if (what.equals("removeOld")) return; // nothing to remove
            HotspotHelper.log(what + " failed reason=" + reason);
            if (reason == WifiP2pManager.BUSY) o.busyCount++;
            if (what.equals("createGroup")) o.creating = false;
            // no immediate retry: the next Tick (15s) tries again
        }
    }

    /** Minimal Context identifying the caller as the shell package. */
    static final class ShellContext extends ContextWrapper {
        ShellContext() { super(null); }

        @Override public String getPackageName() { return HotspotHelper.PKG; }
        @Override public String getOpPackageName() { return HotspotHelper.PKG; }
        @Override public String getAttributionTag() { return null; }
        @Override public Context getApplicationContext() { return this; }
        @Override public Looper getMainLooper() { return Looper.getMainLooper(); }

        @Override
        public ApplicationInfo getApplicationInfo() {
            ApplicationInfo ai = new ApplicationInfo();
            ai.packageName = HotspotHelper.PKG;
            ai.targetSdkVersion = 30;
            return ai;
        }
    }
}
