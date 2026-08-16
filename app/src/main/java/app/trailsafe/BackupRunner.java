package app.trailsafe;

import android.content.Context;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BackupRunner {
    private static final String REMOTE_PARENT = "/my-files";
    private static final String REMOTE_NAME = "TrailSafe";
    private static final String REMOTE_PATH = REMOTE_PARENT + "/" + REMOTE_NAME;
    static final long BATCH_BYTES = 128L * 1024L * 1024L;
    static final int BATCH_FILES = 100;
    static final long MAX_FILE_BYTES = 512L * 1024L * 1024L;
    static final int MAX_DOCUMENTS = 10_000;
    static final int MAX_DEPTH = 64;
    private static final Pattern SKIPPED_ITEMS = Pattern.compile("\\\"skippedItems\\\"\\s*:\\s*(\\d+)");

    private final Context context;
    private final BooleanSupplier stopped;
    private final Consumer<String> progress;
    private boolean remoteReady;

    BackupRunner(Context context, BooleanSupplier stopped, Consumer<String> progress) {
        this.context = context;
        this.stopped = stopped;
        this.progress = progress;
    }

    boolean run() {
        Set<String> sources = TrailSafeStore.sources(context);
        if (sources.isEmpty()) return true;
        if (!CliRunner.connected(context)) {
            status("Connect Proton Drive to start backup");
            return true;
        }
        if (!onWifi()) {
            status("Waiting for Wi-Fi");
            return true;
        }

        File staging = new File(context.getCacheDir(), "backup-staging");
        deleteRecursively(staging);
        if (!staging.mkdirs()) {
            status("Backup failed: cannot create staging folder");
            return false;
        }
        status("Backup running…");

        JSONObject fingerprints = TrailSafeStore.fingerprints(context);
        Counts counts = new Counts();
        Set<String> usedRootNames = new HashSet<>();
        try {
            for (String sourceValue : sources) {
                checkStopped();
                Uri sourceTree = Uri.parse(sourceValue);
                Doc sourceRoot = readDocument(rootDocument(sourceTree));
                if (sourceRoot == null) {
                    counts.failed++;
                    continue;
                }
                File stageRoot = uniqueRoot(staging, sourceRoot.name, sourceValue, usedRootNames);
                Batch batch = new Batch(staging, stageRoot, fingerprints, counts);
                stageFolder(sourceValue, sourceTree, sourceRoot.id, stageRoot, "", batch,
                        new HashSet<>(), 0);
                flush(batch);
            }

            status(counts.summary());
            return counts.failed == 0;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            status("Backup stopped");
            return false;
        } catch (Exception error) {
            status("Backup failed: " + (error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage()));
            return false;
        } finally {
            deleteRecursively(staging);
        }
    }

    private void stageFolder(String sourceValue, Uri sourceTree, String sourceFolderId,
                             File stageFolder, String relativePath, Batch batch,
                             Set<String> ancestors, int depth) throws Exception {
        enterFolder(ancestors, sourceFolderId, depth);
        try {
            for (Doc child : children(sourceTree, sourceFolderId)) {
                checkStopped();
                if (++batch.counts.documents > MAX_DOCUMENTS) {
                    throw new SecurityException("Backup contains too many documents");
                }
                String path = relativePath.isEmpty() ? child.name : relativePath + "/" + child.name;
                if (child.directory()) {
                    stageFolder(sourceValue, sourceTree, child.id,
                            safeStageChild(batch.staging, stageFolder, child.name), path, batch,
                            ancestors, depth + 1);
                    continue;
                }
                if (child.size > MAX_FILE_BYTES) throw new IOException("File exceeds 512 MiB limit: " + path);
                if (!stageFolder.exists() && !stageFolder.mkdirs()) {
                    throw new IllegalStateException("Cannot stage " + path);
                }
                File target = safeStageChild(batch.staging, stageFolder, child.name);
                String fingerprint;
                try (InputStream input = context.getContentResolver().openInputStream(documentUri(sourceTree, child.id));
                     FileOutputStream output = new FileOutputStream(target)) {
                    if (input == null) throw new IllegalStateException("Cannot read " + path);
                    fingerprint = copyAndFingerprint(input, output, stopped);
                }
                String key = sourceValue + "\n" + path;
                if (fingerprint.equals(batch.fingerprints.optString(key, null))) {
                    if (!target.delete()) throw new IOException("Cannot clear unchanged staged file: " + path);
                    batch.counts.skipped++;
                    continue;
                }
                batch.pending.put(key, fingerprint);
                batch.bytes += target.length();
                if (shouldFlush(batch.bytes, batch.pending.size())) flush(batch);
            }
        } finally {
            ancestors.remove(sourceFolderId);
        }
    }

    static void enterFolder(Set<String> ancestors, String documentId, int depth) {
        if (documentId == null || depth > MAX_DEPTH) {
            throw new SecurityException("Backup folder nesting is unsafe");
        }
        if (!ancestors.add(documentId)) throw new SecurityException("Backup folder cycle detected");
    }

    static String copyAndFingerprint(InputStream input, OutputStream output,
                                     BooleanSupplier stopped) throws IOException, InterruptedException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
        byte[] buffer = new byte[64 * 1024];
        long bytes = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (stopped.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Backup stopped");
            }
            bytes += read;
            if (bytes > MAX_FILE_BYTES) throw new IOException("File exceeds 512 MiB limit");
            digest.update(buffer, 0, read);
            output.write(buffer, 0, read);
        }
        StringBuilder result = new StringBuilder("sha256:");
        for (byte value : digest.digest()) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private void flush(Batch batch) throws Exception {
        if (batch.pending.isEmpty()) return;
        checkStopped();
        ensureRemoteFolder();
        progress.accept("Checking " + batch.pending.size() + " files…");
        CliRunner.Result upload = CliRunner.authenticated(context,
                "filesystem", "upload",
                "--json",
                "--file-conflict-strategy", "replace",
                "--folder-conflict-strategy", "merge",
                "--skip-thumbnails",
                batch.root.getAbsolutePath(), REMOTE_PATH);
        checkStopped();
        if (!upload.success()) throw new IOException(lastLine(upload.output));

        int remotelySkipped = remotelySkipped(upload.output, batch.pending.size());
        for (Map.Entry<String, String> entry : batch.pending.entrySet()) {
            batch.fingerprints.put(entry.getKey(), entry.getValue());
        }
        TrailSafeStore.fingerprints(context, batch.fingerprints);
        batch.counts.uploaded += batch.pending.size() - remotelySkipped;
        batch.counts.skipped += remotelySkipped;
        batch.pending.clear();
        batch.bytes = 0;
        deleteRecursively(batch.root);
        status(batch.counts.uploaded + " uploaded; backup running…");
    }

    static boolean shouldFlush(long bytes, int files) {
        return bytes >= BATCH_BYTES || files >= BATCH_FILES;
    }

    static int remotelySkipped(String summary, int candidates) throws IOException {
        Matcher match = SKIPPED_ITEMS.matcher(summary == null ? "" : summary);
        if (!match.find()) throw new IOException("Invalid Proton transfer summary");
        int skipped = Integer.parseInt(match.group(1));
        if (skipped > candidates) throw new IOException("Invalid Proton skipped-file count");
        return skipped;
    }

    private void ensureRemoteFolder() throws IOException {
        if (remoteReady) return;
        CliRunner.Result info = CliRunner.authenticated(context, "filesystem", "info", REMOTE_PATH);
        if (info.success()) {
            remoteReady = true;
            return;
        }
        CliRunner.Result create = CliRunner.authenticated(context,
                "filesystem", "create-folder", REMOTE_PARENT, REMOTE_NAME);
        if (!create.success()) throw new IOException(lastLine(create.output));
        remoteReady = true;
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
        try (Cursor cursor = context.getContentResolver().query(children, projection, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("Folder is unavailable");
            while (cursor.moveToNext()) {
                if (result.size() >= MAX_DOCUMENTS) {
                    throw new SecurityException("Folder contains too many documents");
                }
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
        try (Cursor cursor = context.getContentResolver().query(document, projection, null, null, null)) {
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

    private File uniqueRoot(File staging, String name, String sourceValue,
                            Set<String> usedRootNames) throws IOException {
        String candidate = name;
        if (!usedRootNames.add(candidate)) {
            candidate = name + "-" + Integer.toHexString(sourceValue.hashCode());
            usedRootNames.add(candidate);
        }
        return safeStageChild(staging, staging, candidate);
    }

    static File safeStageChild(File stagingRoot, File parent, String name) throws IOException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\0') >= 0) {
            throw new SecurityException("Unsafe document name");
        }
        File root = stagingRoot.getCanonicalFile();
        File canonicalParent = parent.getCanonicalFile();
        if (!canonicalParent.toPath().startsWith(root.toPath())) {
            throw new SecurityException("Staging path escaped backup folder");
        }
        File child = new File(canonicalParent, name).getCanonicalFile();
        if (!canonicalParent.equals(child.getParentFile())) {
            throw new SecurityException("Document name escaped its folder");
        }
        return child;
    }

    private boolean onWifi() {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        Network network = manager.getActiveNetwork();
        NetworkCapabilities capabilities = network == null ? null : manager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    private void checkStopped() throws InterruptedException {
        if (stopped.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Backup stopped");
        }
    }

    private void status(String value) {
        TrailSafeStore.prefs(context).edit()
                .putString(TrailSafeStore.LAST_STATUS, value)
                .putLong(TrailSafeStore.LAST_RUN, System.currentTimeMillis())
                .apply();
        progress.accept(value);
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

    private static final class Batch {
        final File staging;
        final File root;
        final JSONObject fingerprints;
        final Counts counts;
        final Map<String, String> pending = new LinkedHashMap<>();
        long bytes;

        Batch(File staging, File root, JSONObject fingerprints, Counts counts) {
            this.staging = staging;
            this.root = root;
            this.fingerprints = fingerprints;
            this.counts = counts;
        }
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
        int documents;

        String summary() {
            return uploaded + " uploaded, " + skipped + " unchanged"
                    + (failed == 0 ? "" : ", " + failed + " failed");
        }
    }
}
