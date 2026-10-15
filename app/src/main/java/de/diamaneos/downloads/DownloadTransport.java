// SPDX-License-Identifier: Apache-2.0
package de.diamaneos.downloads;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.ProtocolException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;

/** Fallback happens only before returning the connection, never after consuming bytes. */
public final class DownloadTransport {
    private DownloadTransport() {}
    public interface Connector {
        HttpsURLConnection open(URL url) throws IOException;
    }
    public interface Configure {
        void apply(HttpsURLConnection connection) throws IOException;
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

    public static HttpsURLConnection open(DownloadPolicy policy, boolean apps, String path,
            Connector connector, Configure configure) throws IOException {
        for (int attempt = 0; attempt < (policy.fallback ? 2 : 1); attempt++) {
            HttpsURLConnection connection = null;
            try {
                URL url = policy.url(apps, path, attempt != 0);
                connection = connector.open(url);
                configure.apply(connection);
                connection.setInstanceFollowRedirects(false);
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400 && code != 304) {
                    throw new ProtocolException("Download redirects are refused");
                }
                if (attempt == 0 && policy.fallback && retryable(code)) {
                    connection.disconnect();
                    continue;
                }
                return connection;
            } catch (IOException e) {
                if (connection != null) connection.disconnect();
                if (attempt == 0 && policy.fallback && retryable(e)) continue;
                throw e;
            } catch (RuntimeException e) {
                if (connection != null) connection.disconnect();
                throw e;
            }
        }
        throw new IOException("Download servers unavailable");
    }
}
