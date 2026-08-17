package app.sparenotes;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SessionVault {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "sparenotes_proton_session";
    private static final int MAX_SESSION_BYTES = 1024 * 1024;
    private static final int LOAD = 1;
    private static final int SAVE = 2;
    private static final int REMOVE = 3;
    private static final int OK = 0;
    private static final int MISSING = 1;
    private static final int ERROR = 2;
    private static final int BRIDGE_TIMEOUT_MS = 15_000;

    private SessionVault() {}

    static boolean connected(Context context) {
        return encrypted(context).isFile() || plaintext(context).isFile();
    }

    static synchronized void sealIfNeeded(Context context) {
        File plain = plaintext(context);
        if (!plain.isFile()) return;
        byte[] value = null;
        try {
            if (plain.length() > MAX_SESSION_BYTES) throw new IllegalStateException("Invalid Proton session size");
            value = Files.readAllBytes(plain.toPath());
            encrypt(context, value);
            Files.delete(plain.toPath());
        } catch (Exception error) {
            throw new IllegalStateException("Could not migrate Proton session", error);
        } finally {
            if (value != null) Arrays.fill(value, (byte) 0);
        }
    }

    static Bridge openBridge(Context context) {
        try {
            return new Bridge(context.getApplicationContext());
        } catch (Exception error) {
            throw new IllegalStateException("Could not open Proton session bridge", error);
        }
    }

    private static synchronized void encrypt(Context context, byte[] value) throws Exception {
        if (value.length > MAX_SESSION_BYTES) throw new IllegalStateException("Invalid Proton session size");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] ciphertext = cipher.doFinal(value);
        File temporary = temporary(context);
        try (FileOutputStream stream = new FileOutputStream(temporary);
             DataOutputStream output = new DataOutputStream(stream)) {
            output.writeInt(cipher.getIV().length);
            output.write(cipher.getIV());
            output.write(ciphertext);
            output.flush();
            stream.getFD().sync();
        }
        Files.move(temporary.toPath(), encrypted(context).toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static synchronized byte[] decrypt(Context context) throws Exception {
        File file = encrypted(context);
        if (!file.isFile()) return null;
        if (file.length() > MAX_SESSION_BYTES + 64L) throw new IllegalStateException("Invalid Proton session size");
        try (DataInputStream input = new DataInputStream(new FileInputStream(file))) {
            int ivLength = input.readInt();
            if (ivLength < 12 || ivLength > 32) throw new IllegalStateException("Invalid session data");
            byte[] iv = new byte[ivLength];
            input.readFully(iv);
            ByteArrayOutputStream payloadOutput = new ByteArrayOutputStream();
            byte[] buffer = new byte[8 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) payloadOutput.write(buffer, 0, read);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] value = cipher.doFinal(payloadOutput.toByteArray());
            if (value.length > MAX_SESSION_BYTES) throw new IllegalStateException("Invalid Proton session size");
            return value;
        }
    }

    private static synchronized void remove(Context context) throws Exception {
        Files.deleteIfExists(encrypted(context).toPath());
        Files.deleteIfExists(plaintext(context).toPath());
        Files.deleteIfExists(temporary(context).toPath());
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        SecretKey existing = (SecretKey) store.getKey(KEY_ALIAS, null);
        if (existing != null) return existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static File plaintext(Context context) {
        return new File(CliRunner.dataDirectory(context), "auth-session.json");
    }

    private static File encrypted(Context context) {
        return new File(CliRunner.dataDirectory(context), "auth-session.enc");
    }

    private static File temporary(Context context) {
        return new File(CliRunner.dataDirectory(context), "auth-session.enc.tmp");
    }

    static final class Bridge implements AutoCloseable {
        private final Context context;
        private final String socketName = "sparenotes-vault-" + UUID.randomUUID();
        private final LocalServerSocket server;
        private final Thread thread;
        private volatile boolean closed;

        Bridge(Context context) throws Exception {
            this.context = context;
            server = new LocalServerSocket(socketName);
            thread = new Thread(this::serve, "SpareNotes-vault");
            thread.setDaemon(true);
            thread.start();
        }

        String socketName() {
            return socketName;
        }

        private void serve() {
            while (!closed) {
                try (LocalSocket socket = server.accept()) {
                    if (closed) continue;
                    socket.setSoTimeout(BRIDGE_TIMEOUT_MS);
                    if (!trustedPeer(socket.getPeerCredentials().getUid(), Process.myUid())) {
                        android.util.Log.w("SpareNotes", "Rejected untrusted session bridge peer");
                        continue;
                    }
                    handle(socket);
                } catch (Exception error) {
                    if (!closed) android.util.Log.e("SpareNotes", "Session bridge failed", error);
                }
            }
        }

        static boolean trustedPeer(int peerUid, int appUid) {
            return peerUid == appUid;
        }

        private void handle(LocalSocket socket) throws Exception {
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            try {
                int operation = input.readUnsignedByte();
                if (operation == LOAD) {
                    byte[] value = decrypt(context);
                    if (value == null) {
                        writeResponse(output, MISSING, null);
                    } else {
                        try {
                            writeResponse(output, OK, value);
                        } finally {
                            Arrays.fill(value, (byte) 0);
                        }
                    }
                } else if (operation == SAVE) {
                    int length = input.readInt();
                    if (length < 0 || length > MAX_SESSION_BYTES) {
                        throw new IllegalStateException("Invalid Proton session size");
                    }
                    byte[] value = new byte[length];
                    input.readFully(value);
                    try {
                        encrypt(context, value);
                    } finally {
                        Arrays.fill(value, (byte) 0);
                    }
                    writeResponse(output, OK, null);
                } else if (operation == REMOVE) {
                    remove(context);
                    writeResponse(output, OK, null);
                } else {
                    throw new IllegalStateException("Invalid Proton session operation");
                }
            } catch (Exception error) {
                writeResponse(output, ERROR, null);
            }
        }

        private void writeResponse(DataOutputStream output, int status, byte[] value) throws Exception {
            output.writeByte(status);
            output.writeInt(value == null ? 0 : value.length);
            if (value != null) output.write(value);
            output.flush();
        }

        @Override
        public void close() {
            closed = true;
            try (LocalSocket wake = new LocalSocket()) {
                wake.connect(new LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT));
            } catch (Exception ignored) {}
            try {
                server.close();
                thread.join(1000);
            } catch (Exception ignored) {
                thread.interrupt();
            }
        }
    }
}
