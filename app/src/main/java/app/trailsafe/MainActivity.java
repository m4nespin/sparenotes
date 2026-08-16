package app.trailsafe;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_SOURCE = 10;
    private static final String CLOUD_PATH = "/my-files/TrailSafe";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private volatile boolean connecting;
    private String loginUrl;
    private String connectionError;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        render();
        BackupJobService.schedulePeriodic(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (content != null) render();
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(28), dp(24), dp(40));
        scroll.addView(content);

        TextView title = text("TrailSafe", 30);
        title.setTextColor(Color.BLACK);
        content.addView(title);
        content.addView(text("Automatic, Wi-Fi-only backup to Proton Drive", 16));
        spacer(24);

        section("1. Proton Drive");
        boolean connected = CliRunner.connected(this);
        content.addView(text(connected ? "Connected securely." : connecting
                ? "Waiting for Proton authorization…" : "Not connected.", 16));
        if (connectionError != null) {
            TextView error = text(connectionError, 14);
            error.setTextColor(Color.rgb(150, 0, 0));
            content.addView(error);
        }
        if (!connected && !connecting) {
            addButton("Connect Proton Drive", v -> connect());
        }
        if (connecting) addLoginControls();

        spacer(24);
        section("2. SD card folders");
        if (TrailSafeStore.sources(this).isEmpty()) {
            content.addView(text("No source folders selected.", 16));
        } else {
            for (String source : TrailSafeStore.sources(this)) addSourceRow(source);
        }
        addButton("Add folder", v -> pickSource());

        spacer(24);
        section("Backup");
        content.addView(text("Cloud destination: " + CLOUD_PATH, 15));
        content.addView(text("All files included. Existing cloud files are never deleted. Changed files replace their cloud copy.", 15));
        addButton("Back up now", v -> {
            if (!ready()) return;
            BackupJobService.scheduleNow(this);
            Toast.makeText(this, "Backup started. Keep Wi-Fi connected.", Toast.LENGTH_LONG).show();
            render();
        });

        String status = TrailSafeStore.prefs(this).getString(TrailSafeStore.LAST_STATUS, "Not run yet");
        long lastRun = TrailSafeStore.prefs(this).getLong(TrailSafeStore.LAST_RUN, 0);
        String when = lastRun == 0 ? "" : "\n" + DateFormat.getDateTimeInstance().format(new Date(lastRun));
        TextView statusView = text(status + when, 14);
        statusView.setPadding(0, dp(14), 0, 0);
        content.addView(statusView);

        setContentView(scroll);
    }

    private void connect() {
        connecting = true;
        loginUrl = null;
        connectionError = null;
        render();
        executor.execute(() -> {
            CliRunner.Result result = CliRunner.login(this, line -> {
                if (line.startsWith("https://")) {
                    loginUrl = line;
                    runOnUiThread(this::render);
                }
            });
            runOnUiThread(() -> {
                connecting = false;
                if (result.success()) {
                    loginUrl = null;
                    Toast.makeText(this, "Proton Drive connected.", Toast.LENGTH_LONG).show();
                    BackupJobService.scheduleNow(this);
                } else {
                    connectionError = result.output.isEmpty()
                            ? "Connection failed (code " + result.exitCode + ")."
                            : lastLine(result.output);
                }
                render();
            });
        });
    }

    private void addLoginControls() {
        if (loginUrl == null) {
            content.addView(text("Preparing one-time sign-in link…", 14));
            return;
        }
        content.addView(text("Scan with phone, or open link on another device. Sign in directly with Proton.", 15));
        try {
            ImageView qr = new ImageView(this);
            qr.setImageBitmap(qrCode(loginUrl, 650));
            qr.setAdjustViewBounds(true);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(330), dp(330));
            params.topMargin = dp(12);
            content.addView(qr, params);
        } catch (Exception error) {
            connectionError = "Could not draw sign-in QR code.";
        }
        TextView link = text(loginUrl, 11);
        link.setTextIsSelectable(true);
        link.setPadding(0, dp(10), 0, 0);
        content.addView(link);
        addButton("Open authorization page", v -> openLoginUrl());
        addButton("Cancel", v -> {
            CliRunner.cancelActive();
            connecting = false;
            loginUrl = null;
            render();
        });
    }

    private void openLoginUrl() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(loginUrl)));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "Scan QR code with another device.", Toast.LENGTH_LONG).show();
        }
    }

    private Bitmap qrCode(String value, int size) throws Exception {
        BitMatrix matrix = new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
        }
        return bitmap;
    }

    private void addSourceRow(String source) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView name = text(displayName(Uri.parse(source)), 16);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button remove = new Button(this);
        remove.setText(R.string.remove);
        remove.setOnClickListener(v -> {
            TrailSafeStore.removeSource(this, source);
            TrailSafeStore.forgetSourceFingerprints(this, source);
            render();
        });
        row.addView(remove);
        content.addView(row);
    }

    private boolean ready() {
        if (!CliRunner.connected(this)) {
            Toast.makeText(this, "Connect Proton Drive first.", Toast.LENGTH_LONG).show();
            return false;
        }
        if (TrailSafeStore.sources(this).isEmpty()) {
            Toast.makeText(this, "Add at least one source folder.", Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private void pickSource() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(intent, PICK_SOURCE);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "Folder picker unavailable.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_SOURCE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            TrailSafeStore.addSource(this, uri.toString());
            if (CliRunner.connected(this)) BackupJobService.scheduleNow(this);
            render();
        } catch (SecurityException error) {
            Toast.makeText(this, "Folder permission could not be saved.", Toast.LENGTH_LONG).show();
        }
    }

    private String displayName(Uri treeUri) {
        try {
            Uri document = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            try (Cursor cursor = getContentResolver().query(document,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
            }
        } catch (RuntimeException ignored) {}
        return treeUri.getLastPathSegment();
    }

    private String lastLine(String value) {
        String[] lines = value.trim().split("\\R");
        return lines.length == 0 ? value : lines[lines.length - 1];
    }

    private void section(String value) {
        TextView view = text(value, 20);
        view.setTextColor(Color.BLACK);
        view.setPadding(0, 0, 0, dp(8));
        content.addView(view);
    }

    private void addButton(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(10);
        content.addView(button, params);
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.DKGRAY);
        view.setLineSpacing(0, 1.15f);
        return view;
    }

    private void spacer(int height) {
        View view = new View(this);
        content.addView(view, new LinearLayout.LayoutParams(1, dp(height)));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
