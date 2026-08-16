package app.trailsafe;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

public final class BackupJobService extends JobService {
    private static final int DAILY_JOB = 7101;
    private static final int WIFI_ARRIVAL_JOB = 7102;
    private static final long ONE_DAY = 24L * 60L * 60L * 1000L;

    static void scheduleDaily(android.content.Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        JobInfo job = wifiJob(DAILY_JOB, context)
                .setPersisted(true)
                .setPeriodic(ONE_DAY)
                .build();
        scheduler.schedule(job);
    }

    static void scheduleOnNextWifi(android.content.Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(WIFI_ARRIVAL_JOB) != null) return;
        scheduler.schedule(wifiJob(WIFI_ARRIVAL_JOB, context).setPersisted(true).build());
    }

    private static JobInfo.Builder wifiJob(int id, android.content.Context context) {
        NetworkRequest wifi = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        return new JobInfo.Builder(id, new ComponentName(context, BackupJobService.class))
                .setRequiredNetwork(wifi);
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
