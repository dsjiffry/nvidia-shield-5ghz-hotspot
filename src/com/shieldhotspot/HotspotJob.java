package com.shieldhotspot;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * Background starts without a foreground service:
 *  - JOB_NOW runs right after boot (retried with back-off while adbd is still coming up);
 *  - JOB_HEALTH runs every 15 minutes and relaunches the watchdog if it died.
 * The watchdog itself (shell process) keeps the hotspot up between checks.
 */
public final class HotspotJob extends JobService {
    static final String TAG = "HotspotJob";
    static final int JOB_NOW = 1, JOB_HEALTH = 2;

    static void scheduleAll(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        ComponentName cn = new ComponentName(c, HotspotJob.class);
        js.schedule(new JobInfo.Builder(JOB_NOW, cn)
                .setOverrideDeadline(0)
                .setBackoffCriteria(15000, JobInfo.BACKOFF_POLICY_LINEAR)
                .build());
        js.schedule(new JobInfo.Builder(JOB_HEALTH, cn)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build());
    }

    static void cancelAll(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        js.cancel(JOB_NOW);
        js.cancel(JOB_HEALTH);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (!Hotspot.shouldAutoStart(this)) return false;
        new Thread(() -> {
            boolean retry = false;
            try {
                String r = Hotspot.startWatchdog(this, 30000, false);
                Log.i(TAG, "job " + params.getJobId() + ": " + r);
            } catch (Exception e) {
                Log.w(TAG, "job " + params.getJobId() + " failed: " + e);
                retry = params.getJobId() == JOB_NOW; // adbd may not be up yet right after boot
            }
            jobFinished(params, retry);
        }, "hotspot-job").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
