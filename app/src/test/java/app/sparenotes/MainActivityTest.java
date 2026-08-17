package app.sparenotes;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.lang.reflect.Modifier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

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

    @Test
    public void sessionBridgeAcceptsOnlyItsAppUid() {
        assertTrue(SessionVault.Bridge.trustedPeer(10001, 10001));
        assertFalse(SessionVault.Bridge.trustedPeer(10002, 10001));
    }

    @Test
    public void secureProxyRejectsConnectionsBeyondWorkerLimit() throws Exception {
        ExecutorService workers = SecureNetworkProxy.newWorkerPool();
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int index = 0; index < SecureNetworkProxy.MAX_CLIENTS; index++) {
                workers.execute(() -> {
                    try {
                        release.await();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            try {
                workers.execute(() -> {});
                fail("Worker pool accepted more than its client limit");
            } catch (RejectedExecutionException expected) {}
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }
}
