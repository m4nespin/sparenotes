package app.sparenotes;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BackupForegroundService extends Service {
    private static final String CHANNEL = "backup";
    private static final int NOTIFICATION = 7103;
    private static final long SIX_HOURS = 6L * 60L * 60L * 1000L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean stopped;
    private boolean running;
    private PowerManager.WakeLock wakeLock;

    static void start(Context context) {
        boolean alreadyRunning = SpareNotesStore.prefs(context)
                .getBoolean(SpareNotesStore.BACKUP_RUNNING, false);
        android.content.SharedPreferences.Editor state = SpareNotesStore.prefs(context).edit()
                .putBoolean(SpareNotesStore.BACKUP_RUNNING, true);
        if (!alreadyRunning) {
            state.putString(SpareNotesStore.LAST_STATUS, "Backup starting…")
                    .putLong(SpareNotesStore.LAST_RUN, System.currentTimeMillis());
        }
        state.apply();
        Intent intent = new Intent(context, BackupForegroundService.class);
        try {
            context.startForegroundService(intent);
        } catch (RuntimeException error) {
            SpareNotesStore.prefs(context).edit()
                    .putBoolean(SpareNotesStore.BACKUP_RUNNING, false)
                    .putString(SpareNotesStore.LAST_STATUS, "Backup deferred by Android")
                    .putLong(SpareNotesStore.LAST_RUN, System.currentTimeMillis())
                    .apply();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, "SpareNotes backup", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        showNotification("Backup starting…");
        if (running) return START_NOT_STICKY;

        running = true;
        stopped = false;
        PowerManager power = getSystemService(PowerManager.class);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpareNotes:backup");
        wakeLock.acquire(SIX_HOURS);
        executor.execute(() -> {
            try {
                new BackupRunner(this, () -> stopped, this::showNotification).run();
            } finally {
                SpareNotesStore.prefs(this).edit()
                        .putBoolean(SpareNotesStore.BACKUP_RUNNING, false)
                        .apply();
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        });
        return START_NOT_STICKY;
    }

    private void showNotification(String message) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("SpareNotes")
                .setContentText(message)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    }

    @Override
    public void onDestroy() {
        stopped = true;
        SpareNotesStore.prefs(this).edit()
                .putBoolean(SpareNotesStore.BACKUP_RUNNING, false)
                .apply();
        CliRunner.cancelActive();
        executor.shutdownNow();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
