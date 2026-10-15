// SPDX-License-Identifier: Apache-2.0
package de.diamaneos.downloads;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.net.NoRouteToHostException;
import java.net.ProtocolException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;

/** Fixed origins only. TLS failures and redirects never move a download to the other server. */
public final class DownloadTransport {
    static final int PROBE_TIMEOUT_MS = 3000;
    // Canada goes first only when clearly faster, so near ties stay on the primary.
    static final long TIE_NANOS = 20_000_000L;
    private static final long PROBE_MAX_AGE_NANOS = 10 * 60 * 1_000_000_000L;
    private static final Object probeLock = new Object();
    private static final String[][] probedOrder = new String[2][];
    private static final long[] probedAt = new long[2];

    private DownloadTransport() {}
    public interface Connector {
        HttpsURLConnection open(URL url) throws IOException;
    }
    public interface Configure {
        void apply(HttpsURLConnection connection) throws IOException;
    }
    public interface Sink {
        void write(byte[] buffer, int count) throws IOException;
    }

    /** The servers one download may use, best first. A failure moves it on, never back. */
    public static final class Route {
        final boolean apps;
        private final String[] servers;
        private int current;

        Route(boolean apps, String[] servers) {
            this.apps = apps;
            this.servers = servers.clone();
        }

        public String server() {
            return servers[current];
        }

        boolean next() {
            if (current + 1 >= servers.length) return false;
            current++;
            synchronized (probeLock) {
                probedOrder[apps ? 1 : 0] = null;
            }
            return true;
        }
    }

    /** Automatic sends HEAD for probePath, a small file both servers serve, and keeps the order a while. */
    public static Route route(DownloadPolicy policy, boolean apps, String probePath,
            Connector connector, Configure configure) throws MalformedURLException {
        if (!policy.automatic()) return new Route(apps, policy.servers());
        int key = apps ? 1 : 0;
        synchronized (probeLock) {
            if (probedOrder[key] == null || System.nanoTime() - probedAt[key] > PROBE_MAX_AGE_NANOS) {
                String[] servers = policy.servers();
                probedOrder[key] = order(servers, probe(servers, apps, probePath, connector, configure));
                probedAt[key] = System.nanoTime();
            }
            return new Route(apps, probedOrder[key]);
        }
    }

    /** Fastest answer first. Without an answer from Canada, or a clear lead, the default order stays. */
    static String[] order(String[] servers, long[] elapsed) {
        if (servers.length != 2) return servers.clone();
        boolean swap = elapsed[1] >= 0 && (elapsed[0] < 0 || elapsed[1] + TIE_NANOS < elapsed[0]);
        return swap ? new String[]{servers[1], servers[0]} : servers.clone();
    }

    /** Elapsed nanoseconds per server, or -1 when it gave no 200 in time. */
    static long[] probe(String[] servers, boolean apps, String path, Connector connector,
            Configure configure) throws MalformedURLException {
        long[] elapsed = new long[servers.length];
        Thread[] threads = new Thread[servers.length];
        for (int i = 0; i < servers.length; i++) {
            URL url = DownloadPolicy.url(apps, path, servers[i]);
            int index = i;
            threads[i] = new Thread(() -> elapsed[index] = probe(url, connector, configure),
                    "DownloadProbe");
            threads[i].setDaemon(true);
            threads[i].start();
        }
        // Name lookups have no timeout of their own.
        long deadline = System.nanoTime() + 2L * PROBE_TIMEOUT_MS * 1_000_000L;
        long[] result = new long[servers.length];
        for (int i = 0; i < servers.length; i++) {
            try {
                threads[i].join(Math.max(1, (deadline - System.nanoTime()) / 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            result[i] = threads[i].isAlive() ? -1 : elapsed[i];
        }
        return result;
    }

    private static long probe(URL url, Connector connector, Configure configure) {
        long start = System.nanoTime();
        HttpsURLConnection connection = null;
        try {
            connection = connector.open(url);
            configure.apply(connection);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(PROBE_TIMEOUT_MS);
            connection.setReadTimeout(PROBE_TIMEOUT_MS);
            if (connection.getResponseCode() != 200) return -1;
            return System.nanoTime() - start;
        } catch (IOException | RuntimeException e) {
            return -1;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public static boolean retryable(IOException error) {
        Throwable cause = error;
        int depth = 0;
        while (cause != null && depth++ < 16) {
            if (cause instanceof SSLException || cause instanceof ProtocolException) return false;
            cause = cause.getCause();
        }
        if (cause != null) return false;
        return error instanceof SocketTimeoutException || error instanceof ConnectException
                || error instanceof NoRouteToHostException || error instanceof UnknownHostException
                || error instanceof SocketException;
    }

    private static boolean retryable(int code) {
        return code == 408 || code == 429 || code == 500 || code == 502
                || code == 503 || code == 504;
    }

    /** Bytes already received are kept and checked as a whole at the end, so only TLS stops a resume. */
    static boolean resumable(IOException error) {
        if (Thread.currentThread().isInterrupted()) return false;
        Throwable cause = error;
        for (int depth = 0; cause != null; cause = cause.getCause()) {
            if (cause instanceof SSLException || ++depth > 16) return false;
        }
        return true;
    }

    public static byte[] readBounded(InputStream input, int limit) throws IOException {
        if (limit < 1 || limit > 8 * 1024 * 1024) throw new IllegalArgumentException("Invalid size bound");
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        while (true) {
            int count = input.read(buffer, 0, Math.min(buffer.length, limit - output.size() + 1));
            if (count == -1) return output.toByteArray();
            if (count == 0) throw new IOException("Download made no progress");
            if (count > limit - output.size()) throw new IOException("Download exceeds size bound");
            output.write(buffer, 0, count);
        }
    }

    /** Moves on to the route's next server only before any body byte is read. */
    public static HttpsURLConnection open(Route route, String path, long offset,
            Connector connector, Configure configure) throws IOException {
        if (offset < 0) throw new IllegalArgumentException("Invalid download offset");
        while (true) {
            HttpsURLConnection connection = null;
            try {
                connection = connector.open(DownloadPolicy.url(route.apps, path, route.server()));
                configure.apply(connection);
                connection.setInstanceFollowRedirects(false);
                if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400 && code != 304) {
                    throw new ProtocolException("Download redirects are refused");
                }
                if (retryable(code) && route.next()) {
                    connection.disconnect();
                    continue;
                }
                return connection;
            } catch (IOException e) {
                if (connection != null) connection.disconnect();
                if (retryable(e) && route.next()) continue;
                throw e;
            } catch (RuntimeException e) {
                if (connection != null) connection.disconnect();
                throw e;
            }
        }
    }

    /**
     * Reads a file of known size from an opened connection. When it fails or stalls mid-way,
     * the route's next server continues from the same offset. Returns the final offset.
     */
    public static long copy(Route route, String path, HttpsURLConnection connection, long offset,
            long total, Sink sink, Connector connector, Configure configure) throws IOException {
        if (offset < 0 || total < offset) throw new IllegalArgumentException("Invalid download range");
        HttpsURLConnection current = connection;
        InputStream input = null;
        try {
            input = current.getInputStream();
            byte[] buffer = new byte[16384];
            while (offset < total) {
                int count;
                try {
                    count = input.read(buffer, 0, (int) Math.min(buffer.length, total - offset));
                    if (count == -1) throw new EOFException("Download ended early");
                } catch (IOException e) {
                    if (!resumable(e) || !route.next()) throw e;
                    close(input);
                    input = null;
                    current.disconnect();
                    current = open(route, path, offset, connector, configure);
                    requireRange(current, offset, total);
                    input = current.getInputStream();
                    continue;
                }
                if (count == 0) throw new IOException("Download made no progress");
                sink.write(buffer, count);
                offset += count;
            }
            return offset;
        } finally {
            close(input);
            if (current != connection) current.disconnect();
        }
    }

    /** The other server must send exactly the rest of a file of the same size. */
    static void requireRange(HttpsURLConnection connection, long offset, long total)
            throws IOException {
        String range = "bytes " + offset + "-" + (total - 1) + "/" + total;
        if (connection.getResponseCode() != 206 || connection.getContentLengthLong() != total - offset
                || !range.equals(connection.getHeaderField("Content-Range"))) {
            throw new IOException("Unexpected range from the other download server");
        }
    }

    private static void close(InputStream input) {
        if (input == null) return;
        try {
            input.close();
        } catch (IOException e) {
            // The connection is dropped either way.
        }
    }
}
