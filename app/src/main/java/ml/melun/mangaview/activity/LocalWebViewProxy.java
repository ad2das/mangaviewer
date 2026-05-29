package ml.melun.mangaview.activity;

import java.io.InputStream;
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
        boolean first = executeProxyTask(() -> pipe(a, b));
        boolean second = executeProxyTask(() -> pipe(b, a));
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

    private void pipe(Socket from, Socket to) {
        byte[] buffer = new byte[8192];
        try {
            InputStream input = from.getInputStream();
            OutputStream output = to.getOutputStream();
            int read;
            while((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                output.flush();
            }
        } catch (Exception ignored) {
        } finally {
            closeQuietly(from);
            closeQuietly(to);
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

