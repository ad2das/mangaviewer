package ml.melun.mangaview.activity;

import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import ml.melun.mangaview.mangaview.CustomHttpClient;

final class LocalWebViewProxy {
    private final ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private volatile boolean closed = false;

    static LocalWebViewProxy start() throws Exception {
        ServerSocket socket = new ServerSocket();
        socket.bind(new InetSocketAddress("127.0.0.1", 0));
        LocalWebViewProxy proxy = new LocalWebViewProxy(socket);
        proxy.acceptLoop();
        return proxy;
    }

    private LocalWebViewProxy(ServerSocket serverSocket) {
        this.serverSocket = serverSocket;
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        executor.execute(() -> {
            while(!closed) {
                try {
                    Socket client = serverSocket.accept();
                    if(!executeProxyTask(() -> handle(client)))
                        closeQuietly(client);
                } catch (Exception e) {
                    if(!closed)
                        android.util.Log.d("CaptchaActivity", "NTK WebView proxy accept failed", e);
                }
            }
        });
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(15000);
            InputStream input = client.getInputStream();
            OutputStream output = client.getOutputStream();
            String requestLine = readLine(input);
            if(requestLine == null || !requestLine.startsWith("CONNECT ")) {
                output.write("HTTP/1.1 501 Not Implemented\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                closeQuietly(client);
                return;
            }
            String target = requestLine.substring("CONNECT ".length()).split(" ", 2)[0];
            while(true) {
                String header = readLine(input);
                if(header == null || header.length() == 0)
                    break;
            }
            String host = target;
            int port = 443;
            int colon = target.lastIndexOf(':');
            if(colon > 0) {
                host = target.substring(0, colon);
                port = Integer.parseInt(target.substring(colon + 1));
            }
            Socket upstream = connectUpstream(host, port);
            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();
            pipeBoth(client, upstream);
        } catch (Exception e) {
            if(!closed)
                android.util.Log.d("CaptchaActivity", "NTK WebView proxy connection failed", e);
            closeQuietly(client);
        }
    }

    private Socket connectUpstream(String host, int port) throws Exception {
        Exception lastError = null;
        for(String connectHost : proxyConnectCandidates(host)) {
            Socket upstream = new Socket();
            try {
                upstream.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0));
                upstream.connect(new InetSocketAddress(InetAddress.getByName(connectHost), port), 10000);
                return upstream;
            } catch (Exception e) {
                lastError = e;
                closeQuietly(upstream);
                android.util.Log.d("CaptchaActivity", "NTK WebView proxy upstream failed: " + connectHost, e);
            }
        }
        if(lastError != null)
            throw lastError;
        throw new java.net.UnknownHostException(host);
    }

    private static List<String> proxyConnectCandidates(String host) {
        return proxyConnectCandidates(host, CustomHttpClient.resolveDirectHostForNtkProxy(host));
    }

    private static List<String> proxyConnectCandidates(String host, String directHost) {
        ArrayList<String> candidates = new ArrayList<>();
        addCandidate(candidates, host);
        addCandidate(candidates, directHost);
        return candidates;
    }

    static List<String> proxyConnectCandidatesForTest(String host, String directHost) {
        return proxyConnectCandidates(host, directHost);
    }

    private static void addCandidate(List<String> candidates, String candidate) {
        if(candidate == null || candidate.length() == 0 || candidates.contains(candidate))
            return;
        candidates.add(candidate);
    }

    private void pipeBoth(Socket a, Socket b) {
        boolean first = executeProxyTask(() -> pipe(a, b, true));
        boolean second = executeProxyTask(() -> pipe(b, a, false));
        if(!first || !second) {
            closeQuietly(a);
            closeQuietly(b);
        }
    }

    private boolean executeProxyTask(Runnable task) {
        if(closed || executor.isShutdown())
            return false;
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    private void pipe(Socket from, Socket to, boolean fragmentFirstTlsClientHello) {
        byte[] buffer = new byte[8192];
        try {
            InputStream input = from.getInputStream();
            OutputStream output = to.getOutputStream();
            int read;
            boolean firstPayload = true;
            while((read = input.read(buffer)) >= 0) {
                if(firstPayload && fragmentFirstTlsClientHello && looksLikeTlsClientHello(buffer, 0, read)) {
                    firstPayload = false;
                    writeFragmentedClientHello(output, buffer, 0, read);
                } else {
                    if(read > 0)
                        firstPayload = false;
                    output.write(buffer, 0, read);
                    output.flush();
                }
            }
        } catch (Exception ignored) {
        } finally {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private static boolean looksLikeTlsClientHello(byte[] b, int off, int len) {
        if(b == null || len < 6 || off < 0 || off + len > b.length)
            return false;
        return (b[off] & 0xff) == 0x16
                && (b[off + 1] & 0xff) == 0x03
                && (b[off + 5] & 0xff) == 0x01;
    }

    static boolean looksLikeTlsClientHelloForTest(byte[] b) {
        return looksLikeTlsClientHello(b, 0, b == null ? 0 : b.length);
    }

    private static void writeFragmentedClientHello(OutputStream output, byte[] b, int off, int len) throws Exception {
        if(writeFragmentedClientHelloTlsRecords(output, b, off, len)) {
            android.util.Log.d("CaptchaActivity", "ntk_webview_proxy_sni_tls_record_fragmented bytes=" + len);
            return;
        }
        int first = Math.min(1, len);
        int second = Math.min(7, Math.max(0, len - first));
        writeChunk(output, b, off, first);
        sleepBetweenTlsFragments();
        writeChunk(output, b, off + first, second);
        sleepBetweenTlsFragments();
        if(len - first - second > 0)
            output.write(b, off + first + second, len - first - second);
        output.flush();
        android.util.Log.d("CaptchaActivity", "ntk_webview_proxy_sni_fragmented bytes=" + len);
    }

    static boolean writeFragmentedClientHelloTlsRecordsForTest(OutputStream output, byte[] b) throws Exception {
        return writeFragmentedClientHelloTlsRecords(output, b, 0, b == null ? 0 : b.length);
    }

    private static boolean writeFragmentedClientHelloTlsRecords(OutputStream output, byte[] b, int off, int len) throws Exception {
        if(b == null || off < 0 || len < 6 || off + len > b.length)
            return false;
        int recordPayloadLen = ((b[off + 3] & 0xff) << 8) | (b[off + 4] & 0xff);
        int recordTotalLen = 5 + recordPayloadLen;
        if(recordPayloadLen <= 0 || recordTotalLen > len)
            return false;
        int payloadOffset = off + 5;
        int payloadRemaining = recordPayloadLen;
        int payloadCursor = payloadOffset;
        while(payloadRemaining > 0) {
            int chunk = Math.min(4, payloadRemaining);
            writeTlsRecordChunk(output, b, off, payloadCursor, chunk);
            payloadCursor += chunk;
            payloadRemaining -= chunk;
            sleepBetweenTlsFragments();
        }
        if(recordTotalLen < len)
            output.write(b, off + recordTotalLen, len - recordTotalLen);
        output.flush();
        return true;
    }

    private static void writeTlsRecordChunk(OutputStream output, byte[] b, int recordOff, int payloadOff, int len) throws Exception {
        if(len <= 0)
            return;
        output.write(new byte[]{
                b[recordOff],
                b[recordOff + 1],
                b[recordOff + 2],
                (byte)((len >>> 8) & 0xff),
                (byte)(len & 0xff)
        });
        output.write(b, payloadOff, len);
        output.flush();
    }

    private static void writeChunk(OutputStream output, byte[] b, int off, int len) throws Exception {
        if(len > 0) {
            output.write(b, off, len);
            output.flush();
        }
    }

    private static void sleepBetweenTlsFragments() throws Exception {
        try {
            Thread.sleep(1L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted = new InterruptedIOException("Interrupted while fragmenting WebView TLS ClientHello");
            interrupted.initCause(e);
            throw interrupted;
        }
    }

    private String readLine(InputStream input) throws Exception {
        StringBuilder builder = new StringBuilder();
        int previous = -1;
        int current;
        while((current = input.read()) != -1) {
            if(previous == '\r' && current == '\n') {
                builder.setLength(Math.max(0, builder.length() - 1));
                return builder.toString();
            }
            builder.append((char) current);
            previous = current;
            if(builder.length() > 8192)
                throw new Exception("Proxy header too long");
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    void close() {
        closed = true;
        closeQuietly(serverSocket);
        executor.shutdownNow();
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        try {
            if(closeable != null)
                closeable.close();
        } catch (Exception ignored) {
        }
    }
}

