package app.sparenotes;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
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

    @Test
    public void secureProxyAllowsOnlyAuthorizedProtonTlsTunnels() {
        String authorization = "Basic test";
        SecureNetworkProxy.Target target = SecureNetworkProxy.parseTarget(
                "CONNECT drive-api.proton.me:443 HTTP/1.1\r\n"
                        + "Proxy-Authorization: Basic test\r\n\r\n", authorization);

        assertEquals("drive-api.proton.me", target.host);
        assertEquals(443, target.port);
        assertNull(SecureNetworkProxy.parseTarget(
                "CONNECT drive-api.proton.me:80 HTTP/1.1\r\nProxy-Authorization: Basic test\r\n\r\n",
                authorization));
        assertNull(SecureNetworkProxy.parseTarget(
                "CONNECT proton.me.evil.example:443 HTTP/1.1\r\nProxy-Authorization: Basic test\r\n\r\n",
                authorization));
        assertNull(SecureNetworkProxy.parseTarget(
                "CONNECT drive-api.proton.me:443 HTTP/1.1\r\n\r\n", authorization));
    }
}
