package app.trailsafe;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SessionVault {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "trailsafe_proton_session";

    private SessionVault() {}

    static boolean connected(Context context) {
        return encrypted(context).isFile();
    }

    static synchronized void sealIfNeeded(Context context) {
        if (plaintext(context).isFile()) seal(context);
    }

    static synchronized void seal(Context context) {
        File plain = plaintext(context);
        if (!plain.isFile()) return;
        File temporary = new File(plain.getParentFile(), "auth-session.enc.tmp");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] encrypted = cipher.doFinal(Files.readAllBytes(plain.toPath()));
            try (DataOutputStream output = new DataOutputStream(new FileOutputStream(temporary))) {
                output.writeInt(cipher.getIV().length);
                output.write(cipher.getIV());
                output.write(encrypted);
            }
            Files.move(temporary.toPath(), encrypted(context).toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            if (!plain.delete()) throw new IllegalStateException("Could not protect Proton session");
        } catch (Exception error) {
            temporary.delete();
            throw new IllegalStateException("Could not protect Proton session", error);
        }
    }

    static synchronized void unseal(Context context) {
        File plain = plaintext(context);
        if (plain.isFile()) return;
        File encrypted = encrypted(context);
        if (!encrypted.isFile()) throw new IllegalStateException("Connect Proton Drive first");
        try (DataInputStream input = new DataInputStream(new FileInputStream(encrypted))) {
            int ivLength = input.readInt();
            if (ivLength < 12 || ivLength > 32) throw new IllegalStateException("Invalid session data");
            byte[] iv = new byte[ivLength];
            input.readFully(iv);
            ByteArrayOutputStream payloadOutput = new ByteArrayOutputStream();
            byte[] buffer = new byte[8 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) payloadOutput.write(buffer, 0, read);
            byte[] payload = payloadOutput.toByteArray();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            Files.write(plain.toPath(), cipher.doFinal(payload));
        } catch (Exception error) {
            plain.delete();
            throw new IllegalStateException("Could not unlock Proton session; reconnect TrailSafe", error);
        }
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
}
