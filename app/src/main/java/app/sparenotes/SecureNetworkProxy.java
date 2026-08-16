package app.sparenotes;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class SecureNetworkProxy implements AutoCloseable {
    private static final int MAX_HEADER_BYTES = 16 * 1024;

    private final Network network;
    private final ServerSocket server;
    private final ExecutorService workers = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "SpareNotes-proxy-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Thread acceptThread;
    private final String authorization;
    private final String proxyUrl;
    private volatile boolean closed;

    SecureNetworkProxy(Context context) throws Exception {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        network = connectivity.getActiveNetwork();
        if (network == null) throw new IllegalStateException("No active network");
        String token = UUID.randomUUID().toString();
        authorization = "Basic " + Base64.getEncoder().encodeToString(
                ("sparenotes:" + token).getBytes(StandardCharsets.UTF_8));
        server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getByAddress(
                new byte[]{127, 0, 0, 1}), 0), 16);
        proxyUrl = "http://sparenotes:" + token + "@127.0.0.1:" + server.getLocalPort();
        acceptThread = new Thread(this::accept, "SpareNotes-proxy");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    String proxyUrl() {
        return proxyUrl;
    }

    private void accept() {
        while (!closed) {
            try {
                Socket client = server.accept();
                sockets.add(client);
                try {
                    workers.execute(() -> handle(client));
                } catch (RuntimeException error) {
                    sockets.remove(client);
                    close(client);
                    throw error;
                }
            } catch (Exception error) {
                if (!closed) android.util.Log.e("SpareNotes", "Secure proxy failed", error);
            }
        }
    }

    private void handle(Socket client) {
        Socket upstream = null;
        try {
            client.setSoTimeout(15_000);
            String header = readHeader(client.getInputStream());
            Target target = parseTarget(header, authorization);
            if (target == null) {
                client.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                return;
            }
            upstream = connect(target.host, target.port);
            sockets.add(upstream);
            client.setSoTimeout(0);
            client.getOutputStream().write(
                    "HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            Socket tunnel = upstream;
            Future<?> outbound = workers.submit(() -> pipe(client, tunnel));
            pipe(tunnel, client);
            outbound.cancel(true);
        } catch (Exception error) {
            try {
                client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
            } catch (Exception ignored) {}
        } finally {
            if (upstream != null) sockets.remove(upstream);
            sockets.remove(client);
            close(upstream);
            close(client);
        }
    }

    private Socket connect(String host, int port) throws Exception {
        Exception failure = null;
        for (InetAddress address : network.getAllByName(host)) {
            Socket socket = network.getSocketFactory().createSocket();
            try {
                socket.connect(new InetSocketAddress(address, port), 15_000);
                return socket;
            } catch (Exception error) {
                failure = error;
                close(socket);
            }
        }
        throw failure == null ? new IllegalStateException("Proton host has no address") : failure;
    }

    static Target parseTarget(String header, String requiredAuthorization) {
        if (header == null) return null;
        String[] lines = header.split("\r\n");
        if (lines.length == 0) return null;
        String[] request = lines[0].split(" ");
        if (request.length != 3 || !"CONNECT".equals(request[0])) return null;
        boolean authorized = false;
        for (int index = 1; index < lines.length; index++) {
            int colon = lines[index].indexOf(':');
            if (colon > 0 && "proxy-authorization".equalsIgnoreCase(lines[index].substring(0, colon).trim())
                    && requiredAuthorization.equals(lines[index].substring(colon + 1).trim())) {
                authorized = true;
            }
        }
        if (!authorized) return null;
        int colon = request[1].lastIndexOf(':');
        if (colon <= 0) return null;
        String host = request[1].substring(0, colon).toLowerCase(Locale.ROOT);
        int port;
        try {
            port = Integer.parseInt(request[1].substring(colon + 1));
        } catch (NumberFormatException error) {
            return null;
        }
        if (port != 443 || !(host.equals("proton.me") || host.endsWith(".proton.me"))) return null;
        return new Target(host, port);
    }

    private static String readHeader(InputStream input) throws Exception {
        ByteArrayOutputStream value = new ByteArrayOutputStream();
        int state = 0;
        while (value.size() < MAX_HEADER_BYTES) {
            int next = input.read();
            if (next == -1) throw new IllegalStateException("Proxy request ended early");
            value.write(next);
            if ((state == 0 || state == 2) && next == '\r') state++;
            else if ((state == 1 || state == 3) && next == '\n') state++;
            else state = next == '\r' ? 1 : 0;
            if (state == 4) return value.toString(StandardCharsets.US_ASCII.name());
        }
        throw new IllegalStateException("Proxy request header is too large");
    }

    private static void pipe(Socket inputSocket, Socket outputSocket) {
        try {
            InputStream input = inputSocket.getInputStream();
            OutputStream output = outputSocket.getOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                output.flush();
            }
        } catch (Exception ignored) {}
    }

    private static void close(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (Exception ignored) {}
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
            acceptThread.join(1000);
        } catch (Exception ignored) {
            acceptThread.interrupt();
        }
        for (Socket socket : sockets) close(socket);
        sockets.clear();
        workers.shutdownNow();
    }

    static final class Target {
        final String host;
        final int port;

        Target(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }
}
