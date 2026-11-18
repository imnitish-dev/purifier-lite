package lite.purifier;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/** Once-a-day filter check via Android's JobScheduler; notifies at 10 % and at 0 %. Nothing runs in between. */
public class FilterCheck extends JobService {
    static final int JOB_ID = 1;

    /** Called when the app opens and when the widget updates. */
    // ponytail: not persisted across reboots (that needs another permission); the next app open or widget update re-schedules it
    static void schedule(Context c) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js.getPendingJob(JOB_ID) != null) return;
            // No "needs network" constraint: that requires an extra permission. If offline, it just tries again tomorrow.
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, FilterCheck.class))
                    .setPeriodic(24 * 60 * 60 * 1000L)
                    .build());
        } catch (Exception e) { Log.w("Purifier", "schedule filter check", e); } // never let reminders break the app
    }

    @Override public boolean onStartJob(JobParameters p) {
        new Thread(() -> {
            try {
                Cloud cloud = new Cloud(this);
                String did = cloud.sp.getString("did", null);
                if (cloud.loggedIn() && did != null) {
                    Object life = cloud.getProp(did, Cloud.PROPS[Cloud.FILTER][0], Cloud.PROPS[Cloud.FILTER][1]);
                    if (life instanceof Number) notifyFilter(this, cloud.sp, ((Number) life).intValue());
                }
            } catch (Exception e) { Log.w("Purifier", "filter check", e); }
            jobFinished(p, false);
        }).start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters p) { return true; }

    /** 0 = fine, 1 = low (≤10 %), 2 = used up. Notifies once per level; resets when a new filter is fitted. */
    static int level(int life) { return life <= 0 ? 2 : life <= 10 ? 1 : 0; }

    static void notifyFilter(Context c, SharedPreferences sp, int life) {
        int level = level(life), last = sp.getInt("filterAlert", 0);
        if (level == last || (level != 0 && level < last)) return;
        sp.edit().putInt("filterAlert", level).apply();
        if (level == 0) return; // filter replaced: arm the reminders again

        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("filter", "Filter reminders", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        nm.notify(1, new Notification.Builder(c, "filter")
                .setSmallIcon(R.drawable.ic_fg)
                .setContentTitle(level == 2 ? "Purifier filter is used up" : "Purifier filter at " + life + "%")
                .setContentText(level == 2 ? "Replace it now: the purifier can't clean the air well anymore." : "Time to order a replacement filter.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build());
    }
}
