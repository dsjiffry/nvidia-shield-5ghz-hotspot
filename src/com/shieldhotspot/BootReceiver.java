package com.shieldhotspot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Schedules the start job after boot and after app updates (if the user left the hotspot on). */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        if (Hotspot.shouldAutoStart(c)) HotspotJob.scheduleAll(c);
    }
}
