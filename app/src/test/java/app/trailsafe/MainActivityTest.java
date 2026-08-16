package app.trailsafe;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Modifier;

public final class MainActivityTest {
    @Test
    public void acceptsOnlyProtonAccountHttpsUrls() {
        assertTrue(MainActivity.trustedProtonLoginUrl(
                "https://account.proton.me/desktop/login#one-time-secret"));
        assertFalse(MainActivity.trustedProtonLoginUrl(
                "http://account.proton.me/desktop/login#one-time-secret"));
        assertFalse(MainActivity.trustedProtonLoginUrl(
                "https://account.proton.me.evil.example/desktop/login#one-time-secret"));
        assertFalse(MainActivity.trustedProtonLoginUrl(
                "https://account.proton.me@evil.example/desktop/login#one-time-secret"));
        assertFalse(MainActivity.trustedProtonLoginUrl("https://account.proton.me/"));
    }

    @Test
    public void serializesAllCredentialUsingCliOperations() throws Exception {
        assertTrue(Modifier.isSynchronized(CliRunner.class
                .getDeclaredMethod("login", android.content.Context.class, CliRunner.LineListener.class)
                .getModifiers()));
        assertTrue(Modifier.isSynchronized(CliRunner.class
                .getDeclaredMethod("authenticated", android.content.Context.class, String[].class)
                .getModifiers()));
    }
}
