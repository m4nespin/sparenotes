package app.trailsafe;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BackupJobService extends JobService {
    private static final int PERIODIC_JOB = 7101;
    private static final int NOW_JOB = 7102;
    private static final long FIFTEEN_MINUTES = 15L * 60L * 1000L;
    private static final String REMOTE_PARENT = "/my-files";
    private static final String REMOTE_NAME = "TrailSafe";
    private static final String REMOTE_PATH = REMOTE_PARENT + "/" + REMOTE_NAME;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean stopped;

    static void schedulePeriodic(android.content.Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        JobInfo job = new JobInfo.Builder(PERIODIC_JOB, new ComponentName(context, BackupJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(FIFTEEN_MINUTES)
                .build();
        scheduler.schedule(job);
    }

    static void scheduleNow(android.content.Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        JobInfo job = new JobInfo.Builder(NOW_JOB, new ComponentName(context, BackupJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build();
        scheduler.schedule(job);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        stopped = false;
        executor.execute(() -> {
            boolean retry = !runBackup();
            jobFinished(params, retry && !stopped);
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        stopped = true;
        CliRunner.cancelActive();
        return true;
    }

    private boolean runBackup() {
        Set<String> sources = TrailSafeStore.sources(this);
        if (sources.isEmpty()) return true;
        if (!CliRunner.connected(this)) {
            status("Connect Proton Drive to start backup");
            return true;
        }
        if (!onWifi()) return false;

        File staging = new File(getCacheDir(), "backup-staging");
        deleteRecursively(staging);
        if (!staging.mkdirs()) {
            status("Backup failed: cannot create staging folder");
            return false;
        }

        JSONObject fingerprints = TrailSafeStore.fingerprints(this);
        Map<String, String> pending = new LinkedHashMap<>();
        List<File> uploadRoots = new ArrayList<>();
        Counts counts = new Counts();
        try {
            for (String sourceValue : sources) {
                if (stopped) return false;
                Uri sourceTree = Uri.parse(sourceValue);
                Doc sourceRoot = readDocument(rootDocument(sourceTree));
                if (sourceRoot == null) {
                    counts.failed++;
                    continue;
                }
                File stageRoot = uniqueRoot(staging, safeName(sourceRoot.name), sourceValue);
                stageFolder(sourceValue, sourceTree, sourceRoot.id, stageRoot, "", fingerprints, pending, counts);
                if (stageRoot.isDirectory()) uploadRoots.add(stageRoot);
            }

            if (counts.failed > 0) {
                status(counts.summary());
                return false;
            }
            if (pending.isEmpty()) {
                status("0 uploaded, " + counts.skipped + " unchanged");
                return true;
            }
            if (!ensureRemoteFolder()) return false;

            List<String> arguments = new ArrayList<>(List.of(
                    "filesystem", "upload",
                    "--file-conflict-strategy", "replace",
                    "--folder-conflict-strategy", "merge",
                    "--skip-thumbnails"
            ));
            for (File root : uploadRoots) arguments.add(root.getAbsolutePath());
            arguments.add(REMOTE_PATH);
            CliRunner.Result upload = CliRunner.authenticated(this, arguments.toArray(new String[0]));
            if (!upload.success()) {
                status("Backup failed: " + lastLine(upload.output));
                return false;
            }
            for (Map.Entry<String, String> entry : pending.entrySet()) {
                fingerprints.put(entry.getKey(), entry.getValue());
            }
            TrailSafeStore.fingerprints(this, fingerprints);
            counts.uploaded = pending.size();
            status(counts.summary());
            return true;
        } catch (Exception error) {
            status("Backup failed: " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
            return false;
        } finally {
            deleteRecursively(staging);
        }
    }

    private boolean ensureRemoteFolder() {
        CliRunner.Result info = CliRunner.authenticated(this, "filesystem", "info", REMOTE_PATH);
        if (info.success()) return true;
        CliRunner.Result create = CliRunner.authenticated(this,
                "filesystem", "create-folder", REMOTE_PARENT, REMOTE_NAME);
        if (create.success()) return true;
        status("Backup failed: " + lastLine(create.output));
        return false;
    }

    private void stageFolder(String sourceValue, Uri sourceTree, String sourceFolderId,
                             File stageFolder, String relativePath, JSONObject fingerprints,
                             Map<String, String> pending, Counts counts) throws Exception {
        for (Doc child : children(sourceTree, sourceFolderId)) {
            if (stopped) throw new InterruptedException("Backup stopped");
            String path = relativePath.isEmpty() ? child.name : relativePath + "/" + child.name;
            if (child.directory()) {
                stageFolder(sourceValue, sourceTree, child.id,
                        new File(stageFolder, safeName(child.name)), path, fingerprints, pending, counts);
                continue;
            }
            String key = sourceValue + "\n" + path;
            String fingerprint = child.size + ":" + child.modified;
            if (fingerprint.equals(fingerprints.optString(key, null))) {
                counts.skipped++;
                continue;
            }
            if (!stageFolder.exists() && !stageFolder.mkdirs()) {
                throw new IllegalStateException("Cannot stage " + path);
            }
            File target = new File(stageFolder, safeName(child.name));
            try (InputStream input = getContentResolver().openInputStream(documentUri(sourceTree, child.id));
                 FileOutputStream output = new FileOutputStream(target)) {
                if (input == null) throw new IllegalStateException("Cannot read " + path);
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (stopped) throw new InterruptedException("Backup stopped");
                    output.write(buffer, 0, read);
                }
            }
            pending.put(key, fingerprint);
        }
    }

    private List<Doc> children(Uri tree, String parentId) {
        List<Doc> result = new ArrayList<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        try (Cursor cursor = getContentResolver().query(children, projection, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("Folder is unavailable");
            while (cursor.moveToNext()) {
                result.add(new Doc(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                        cursor.isNull(3) ? 0 : cursor.getLong(3),
                        cursor.isNull(4) ? 0 : cursor.getLong(4)));
            }
        }
        return result;
    }

    private Doc readDocument(Uri document) {
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        try (Cursor cursor = getContentResolver().query(document, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return new Doc(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                        cursor.isNull(3) ? 0 : cursor.getLong(3),
                        cursor.isNull(4) ? 0 : cursor.getLong(4));
            }
        } catch (RuntimeException ignored) {}
        return null;
    }

    private Uri rootDocument(Uri tree) {
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
    }

    private Uri documentUri(Uri tree, String id) {
        return DocumentsContract.buildDocumentUriUsingTree(tree, id);
    }

    private File uniqueRoot(File staging, String name, String sourceValue) {
        File root = new File(staging, name);
        if (!root.exists()) return root;
        return new File(staging, name + "-" + Integer.toHexString(sourceValue.hashCode()));
    }

    private String safeName(String name) {
        return (name == null ? "unnamed" : name).replace('/', '_').replace('\0', '_');
    }

    private boolean onWifi() {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        Network network = manager.getActiveNetwork();
        NetworkCapabilities capabilities = network == null ? null : manager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    private void status(String value) {
        TrailSafeStore.prefs(this).edit()
                .putString(TrailSafeStore.LAST_STATUS, value)
                .putLong(TrailSafeStore.LAST_RUN, System.currentTimeMillis())
                .apply();
    }

    private String lastLine(String value) {
        if (value == null || value.trim().isEmpty()) return "Proton Drive command failed";
        String[] lines = value.trim().split("\\R");
        return lines[lines.length - 1];
    }

    private void deleteRecursively(File file) {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }

    private static final class Doc {
        final String id;
        final String name;
        final String mime;
        final long size;
        final long modified;

        Doc(String id, String name, String mime, long size, long modified) {
            this.id = id;
            this.name = name == null ? "unnamed" : name;
            this.mime = mime;
            this.size = size;
            this.modified = modified;
        }

        boolean directory() {
            return DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
        }
    }

    private static final class Counts {
        int uploaded;
        int skipped;
        int failed;

        String summary() {
            return uploaded + " uploaded, " + skipped + " unchanged" + (failed == 0 ? "" : ", " + failed + " failed");
        }
    }
}
