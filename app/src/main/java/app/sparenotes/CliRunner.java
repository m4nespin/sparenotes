package app.sparenotes;

import android.annotation.SuppressLint;
import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class CliRunner {
    private static final String RUNTIME_VERSION = "2";
    private static final String[] RUNTIME_FILES = {
            "libc.musl-aarch64.so.1",
            "libgcc_s.so.1",
            "libstdc++.so.6",
            "ca-certificates.crt",
            "resolv.conf"
    };
    private static volatile Process activeProcess;

    interface LineListener {
        void onLine(String line);
    }

    static final class Result {
        final int exitCode;
        final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        boolean success() {
            return exitCode == 0;
        }
    }

    private CliRunner() {}

    @SuppressLint("ApplySharedPref") // Runtime install must be durable before commands start.
    static synchronized void prepare(Context context) {
        File runtime = runtimeDirectory(context);
        if (!runtime.exists() && !runtime.mkdirs()) return;
        String installed = SpareNotesStore.prefs(context).getString("runtime_version", null);
        boolean runtimeChanged = !RUNTIME_VERSION.equals(installed);
        if (runtimeChanged) {
            for (String name : RUNTIME_FILES) copyAsset(context, "runtime/" + name, new File(runtime, name));
        }
        installPassBridge(context, runtime);
        File data = dataDirectory(context);
        data.mkdirs();
        File resolver = new File(data, "resolv.conf");
        if (runtimeChanged || !resolver.exists()) copyAsset(context, "runtime/resolv.conf", resolver);
        SessionVault.sealIfNeeded(context);
        if (runtimeChanged) {
            SpareNotesStore.prefs(context).edit().putString("runtime_version", RUNTIME_VERSION).commit();
        }
    }

    static boolean connected(Context context) {
        return SessionVault.connected(context);
    }

    static synchronized Result login(Context context, LineListener listener) {
        prepare(context);
        return runRaw(context, List.of("auth", "login"), listener);
    }

    static synchronized Result authenticated(Context context, String... arguments) {
        prepare(context);
        return runRaw(context, Arrays.asList(arguments), null);
    }

    static void cancelActive() {
        Process process = activeProcess;
        if (process != null) process.destroy();
    }

    static File dataDirectory(Context context) {
        return new File(context.getFilesDir(), "proton-cli");
    }

    private static Result runRaw(Context context, List<String> arguments, LineListener listener) {
        File runtime = runtimeDirectory(context);
        File nativeDirectory = new File(context.getApplicationInfo().nativeLibraryDir);
        List<String> command = new ArrayList<>();
        command.add(new File(nativeDirectory, "libcompatwrap.so").getAbsolutePath());
        command.add(new File(nativeDirectory, "libmusl_loader.so").getAbsolutePath());
        command.add("--library-path");
        command.add(runtime.getAbsolutePath());
        command.add(new File(nativeDirectory, "libproton_drive_cli.so").getAbsolutePath());
        command.addAll(arguments);

        StringBuilder output = new StringBuilder();
        try (SessionVault.Bridge bridge = SessionVault.openBridge(context);
             SecureNetworkProxy proxy = new SecureNetworkProxy(context)) {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(dataDirectory(context))
                    .redirectErrorStream(true);
            Map<String, String> environment = builder.environment();
            environment.put("HOME", dataDirectory(context).getAbsolutePath());
            environment.put("XDG_CONFIG_HOME", new File(dataDirectory(context), "config").getAbsolutePath());
            environment.put("XDG_STATE_HOME", new File(dataDirectory(context), "state").getAbsolutePath());
            environment.put("XDG_CACHE_HOME", new File(dataDirectory(context), "cache").getAbsolutePath());
            environment.put("SSL_CERT_FILE", new File(runtime, "ca-certificates.crt").getAbsolutePath());
            environment.put("PROTON_DRIVE_CACHE_DIR", dataDirectory(context).getAbsolutePath());
            environment.put("PATH", runtime.getAbsolutePath() + ":"
                    + environment.getOrDefault("PATH", "/system/bin"));
            environment.put("PROTON_DRIVE_CREDENTIALS_STORE", "pass");
            environment.put("PROTON_DRIVE_LOG_LEVEL", "WARNING");
            environment.put("SPARENOTES_VAULT_SOCKET", bridge.socketName());
            environment.put("HTTP_PROXY", proxy.proxyUrl());
            environment.put("HTTPS_PROXY", proxy.proxyUrl());
            environment.put("http_proxy", proxy.proxyUrl());
            environment.put("https_proxy", proxy.proxyUrl());
            environment.put("NO_PROXY", "127.0.0.1,localhost");
            environment.put("no_proxy", "127.0.0.1,localhost");
            Process process = builder.start();
            activeProcess = process;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                    if (listener != null) listener.onLine(line);
                }
            }
            return new Result(process.waitFor(), output.toString().trim());
        } catch (Exception error) {
            return new Result(-1, error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        } finally {
            activeProcess = null;
        }
    }

    private static File runtimeDirectory(Context context) {
        return new File(context.getFilesDir(), "musl");
    }

    private static void installPassBridge(Context context, File runtime) {
        File packaged = new File(context.getApplicationInfo().nativeLibraryDir, "libpass_bridge.so");
        File link = new File(runtime, "pass");
        try {
            if (Files.isSymbolicLink(link.toPath())
                    && Files.readSymbolicLink(link.toPath()).equals(packaged.toPath())) return;
            Files.deleteIfExists(link.toPath());
            Files.createSymbolicLink(link.toPath(), packaged.toPath());
        } catch (Exception error) {
            throw new IllegalStateException("Could not install credential bridge", error);
        }
    }

    private static void copyAsset(Context context, String asset, File destination) {
        try (InputStream input = context.getAssets().open(asset);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        } catch (Exception error) {
            throw new IllegalStateException("Could not install SpareNotes runtime", error);
        }
    }
}
