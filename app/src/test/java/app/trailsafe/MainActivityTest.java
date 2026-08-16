package app.trailsafe;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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
}
