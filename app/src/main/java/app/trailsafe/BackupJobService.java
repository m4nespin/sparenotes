package app.trailsafe;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;

public final class BackupJobService extends JobService {
    private static final int PERIODIC_JOB = 7101;
    private static final int LEGACY_NOW_JOB = 7102;
    private static final long FIFTEEN_MINUTES = 15L * 60L * 1000L;

    static void schedulePeriodic(android.content.Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        scheduler.cancel(LEGACY_NOW_JOB);
        JobInfo job = new JobInfo.Builder(PERIODIC_JOB, new ComponentName(context, BackupJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(FIFTEEN_MINUTES)
                .build();
        scheduler.schedule(job);
    }

    static void scheduleNow(android.content.Context context) {
        BackupForegroundService.start(context);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        BackupForegroundService.start(this);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
