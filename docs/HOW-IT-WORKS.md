# How it works

## The problem

- SHIELD TV (Android 11) has no hotspot UI, and Android only lets privileged apps start tethering.
- A standard soft-AP hotspot on SHIELD can only use **2.4GHz**. Android refuses a 5GHz soft AP
  without a Wi-Fi country code (SHIELD has no SIM, so none is set). Even with one set, the Wi-Fi
  firmware marks every 5GHz channel `NO-IR` for AP mode:

  ```
  hostapd: Frequency 5180 (primary) not allowed for AP mode, flags: 0x853 NO-IR
  ```

- **Wi-Fi Direct** groups *are* allowed on 5GHz, but Android starts them in **local-only** mode
  (DHCP, but no route to the internet). That's why other apps add an HTTP proxy.

## The approach

1. **Privilege without root.** With *Network debugging* on, adbd listens on port 5555. The app talks
   to it on `127.0.0.1:5555` with a small built-in adb client (classic RSA auth; the user approves
   the key once). Through it the app runs

   ```
   CLASSPATH=<app apk> app_process /system/bin com.shieldhotspot.HotspotHelper <command>
   ```

   which executes the helper as the **shell user**. Shell holds `TETHER_PRIVILEGED`,
   `NETWORK_SETTINGS`, `CONNECTIVITY_INTERNAL` and `WRITE_SECURE_SETTINGS`.

2. **5GHz mode (`watchp2p`).**
   - The helper creates its own `WifiP2pManager` (with a minimal shell-user `Context`) and creates a
     group with `GROUP_OWNER_BAND_5GHZ`, named `DIRECT-SH-<name>`. `NETWORK_SETTINGS` exempts these
     calls from Android's location-permission checks, so the app needs no location permission.
   - Android's Tethering service puts the group's interface (`p2p-wlan0-N`) into
     `LocalHotspotState`. The helper calls `ITetheringConnector.untether()` then `tether()` on that
     interface, which moves it to `TetheredState`: NAT and DHCP with the current default network
     (Ethernet) as upstream.
   - Watchdog: the group is checked every 15 s and recreated if needed. Tether state is checked
     every 3 s. If Wi-Fi Direct is stuck for about 2 minutes, Wi-Fi is restarted.

3. **2.4GHz mode (`watch`).** `IWifiManager.setSoftApConfiguration()` followed by
   `ITetheringConnector.startTethering(TETHERING_WIFI)`, re-checked every 10 s.

4. **Lifetime.** The helper is fully detached (`setsid`, `nohup`, SIGHUP ignored) and survives the
   adb session. A single-instance lock (abstract socket) prevents duplicates. The app schedules a
   `JobScheduler` job at boot (retrying until adbd is up) and a 15-minute health job that relaunches
   the helper if it died. No foreground service is needed.

5. **Status.** The helper writes `/data/local/tmp/shieldhotspot.status` (key=value) every few
   seconds, and the TV interface reads it over the same adb connection.

## Files

| File | Role |
|---|---|
| `src/.../AdbClient.java` | Minimal adb protocol client (CNXN/AUTH/OPEN/WRTE/CLSE, RSA key in app storage) |
| `src/.../HotspotHelper.java` | Runs as shell: tethering calls, watchdogs, status file |
| `src/.../P2pOwner.java` | Owns the 5GHz Wi-Fi Direct group from the shell process |
| `src/.../Hotspot.java` | App side: settings, launching/stopping the helper, reading status |
| `src/.../HotspotJob.java` / `BootReceiver.java` | Boot start and periodic health check |
| `src/.../MainActivity.java` | Android TV interface |
