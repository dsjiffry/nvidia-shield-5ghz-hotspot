package com.shieldhotspot;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.ConnectException;

/**
 * Settings plus the shell-side (adb) part: launching / stopping the privileged watchdog
 * (HotspotHelper running as the shell user) and reading its status file.
 */
public final class Hotspot {
    static final String PREFS = "hotspot";
    static final String LOG = "/data/local/tmp/shieldhotspot.log";
    static final String STATUS = "/data/local/tmp/shieldhotspot.status";
    static final String NICE = "shieldhotspot_watch";
    /** Bracket trick so pkill/pgrep don't match the shell running them. */
    static final String NICE_RE = "'shieldhotspot_[w]atch'";
    static final int BAND_5 = 2, BAND_24 = 1;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String ssid(Context c) { return prefs(c).getString("ssid", "Shield"); }
    static String pass(Context c) { return prefs(c).getString("pass", ""); }
    static int band(Context c) { return prefs(c).getInt("band", BAND_5); }
    static boolean autoStart(Context c) { return prefs(c).getBoolean("autostart", true); }
    /** True while the user wants the hotspot on (pressed Start, not Stop). */
    static boolean enabled(Context c) {
        // Installs from v1 have saved settings but no flag: they were running, so treat them as enabled.
        return prefs(c).getBoolean("enabled", prefs(c).contains("ssid"));
    }
    /** True once the app has successfully used adb (the user approved the debugging prompt). */
    static boolean adbReady(Context c) {
        return prefs(c).getBoolean("adbReady", prefs(c).contains("ssid"));
    }

    static boolean shouldAutoStart(Context c) { return enabled(c) && autoStart(c); }

    /** Name devices will see. Wi-Fi Direct (5GHz) names must start with "DIRECT-xy-". */
    static String broadcastName(Context c) {
        return band(c) == BAND_5 ? P2pOwner.TAG_PREFIX + ssid(c) : ssid(c);
    }

    private static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String helper(Context c) {
        return "CLASSPATH=" + q(c.getPackageCodePath()) + " app_process /system/bin com.shieldhotspot.HotspotHelper";
    }

    private static AdbClient adb(Context c, int authTimeoutMs) throws Exception {
        return AdbClient.connect(c.getFilesDir(), "127.0.0.1", 5555, authTimeoutMs);
    }

    /** Thrown when adbd isn't reachable: Network debugging is off (or the device is still booting). */
    static final class AdbOffException extends Exception {
        AdbOffException(Throwable cause) { super("Network debugging is off", cause); }
    }

    private static AdbClient connect(Context c, int authTimeoutMs) throws Exception {
        try {
            return adb(c, authTimeoutMs);
        } catch (ConnectException e) {
            throw new AdbOffException(e);
        }
    }

    /**
     * Launches the watchdog for the current settings. With restart=false this is a no-op when a
     * watchdog is already running (used by boot / periodic health checks).
     */
    static synchronized String startWatchdog(Context c, int authTimeoutMs, boolean restart) throws Exception {
        try (AdbClient a = connect(c, authTimeoutMs)) {
            if (!restart && a.shell("pgrep -f " + NICE_RE + " >/dev/null && echo yes", 10000).trim().equals("yes")) {
                return "already running";
            }
            a.shell("pkill -f " + NICE_RE, 10000);
            String args;
            if (band(c) == BAND_5) {
                a.shell("svc wifi enable", 15000);
                args = "watchp2p " + q(ssid(c)) + " " + q(pass(c));
            } else {
                args = "watch " + q(ssid(c)) + " " + q(pass(c)) + " " + BAND_24;
            }
            // Fully detach (double fork + new session + ignore SIGHUP) so the watchdog outlives this adb session.
            String inner = "trap '' HUP; export CLASSPATH=" + q(c.getPackageCodePath()) + "; exec app_process /system/bin --nice-name="
                    + NICE + " com.shieldhotspot.HotspotHelper " + args;
            a.shell("echo \"launch $(date)\" > " + LOG + "; (setsid nohup sh -c " + q(inner) + " >> " + LOG
                    + " 2>&1 < /dev/null &) ; sleep 1", 15000);
            return "launched";
        }
    }

    /** Stops the watchdog and tears down the Wi-Fi Direct group / soft-AP. */
    static synchronized void stopWatchdog(Context c) throws Exception {
        try (AdbClient a = connect(c, 60000)) {
            a.shell("pkill -f " + NICE_RE + "; " + helper(c) + " stopall", 30000);
        }
    }

    /** Live status, parsed from the file the watchdog rewrites every few seconds. */
    static final class Status {
        boolean running, up, adbOff;
        String mode = "off", name = "", tether = "", upstream = "", error;
        int freq, clients = -1;
        long ageMs = Long.MAX_VALUE;

        String bandLabel() {
            if (freq >= 5000) return "5 GHz \u00b7 channel " + ((freq - 5000) / 5);
            if (freq >= 2400) return "2.4 GHz \u00b7 channel " + ((freq - 2407) / 5);
            return mode.equals("5ghz") ? "5 GHz" : mode.equals("2.4ghz") ? "2.4 GHz" : "\u2014";
        }

        String upstreamLabel() {
            if (upstream == null || upstream.isEmpty()) return "Waiting for a network";
            return upstream.startsWith("eth") ? "Ethernet (" + upstream + ")" : upstream;
        }
    }

    static Status status(Context c) {
        Status s = new Status();
        try (AdbClient a = connect(c, 60000)) {
            String out = a.shell("pgrep -f " + NICE_RE + " >/dev/null && echo running=true; cat " + STATUS + " 2>/dev/null", 10000);
            long time = 0;
            for (String line : out.split("\n")) {
                int i = line.indexOf('=');
                if (i < 0) continue;
                String k = line.substring(0, i).trim(), v = line.substring(i + 1).trim();
                switch (k) {
                    case "running": s.running = true; break;
                    case "mode": s.mode = v; break;
                    case "name": s.name = v; break;
                    case "up": s.up = v.equals("true"); break;
                    case "freq": s.freq = parseInt(v); break;
                    case "clients": s.clients = parseInt(v); break;
                    case "tether": s.tether = v; break;
                    case "upstream": s.upstream = v; break;
                    case "time": time = parseLong(v); break;
                }
            }
            if (time > 0) s.ageMs = System.currentTimeMillis() - time;
            if (!s.running || s.ageMs > 30000) s.up = false;
        } catch (AdbOffException e) {
            s.adbOff = true;
        } catch (Exception e) {
            s.error = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        return s;
    }

    private static int parseInt(String v) {
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return 0; }
    }

    private static long parseLong(String v) {
        try { return Long.parseLong(v); } catch (NumberFormatException e) { return 0; }
    }
}
