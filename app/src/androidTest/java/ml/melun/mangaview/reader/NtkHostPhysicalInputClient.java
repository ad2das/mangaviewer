package ml.melun.mangaview.reader;

import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Guest side of the emulator-gRPC physical touchscreen protocol. */
public final class NtkHostPhysicalInputClient {
    public static final int PROTOCOL = 2;
    public static final int GESTURES = 59;
    public static final int STEPS = 4;
    public static final int EVENTS = 295;
    public static final int SAMPLE_INTERVAL_MS = 12;
    public static final int GESTURE_GAP_MS = 24;
    public static final int MAX_LATENESS_MS = 16;
    private static final int RELEASE_MAGIC = 0x4e545232;

    private NtkHostPhysicalInputClient() {
    }

    public static PreparedPlan prepare(
            String host, int port, String runToken,
            int x, int startY, int endY) throws Exception {
        if(host == null || host.trim().isEmpty() || port <= 0)
            throw new AssertionError("Invalid host physical-input endpoint");
        if(runToken == null || runToken.trim().isEmpty())
            throw new AssertionError("Physical-input run token is empty");
        if(startY <= endY)
            throw new AssertionError("Physical forward swipe must move upward");

        Socket socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(15_000);
        socket.connect(new InetSocketAddress(host, port), 5_000);
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                socket.getOutputStream(), StandardCharsets.UTF_8));
        try {
            JSONObject hello = readJsonLine(reader, "hello");
            requireType(hello, "hello", runToken, false);
            if(hello.getInt("protocol") != PROTOCOL)
                throw new AssertionError("Host producer protocol mismatch: " + hello);

            JSONObject arm = new JSONObject()
                    .put("type", "arm")
                    .put("protocol", PROTOCOL)
                    .put("runToken", runToken);
            writeJsonLine(writer, arm);
            JSONObject armed = readJsonLine(reader, "armed");
            requireType(armed, "armed", runToken, true);

            long nonce = positiveNonce();
            int[] directions = directions();
            String digest = planDigest(x, startY, endY, directions);
            JSONArray directionJson = new JSONArray();
            for(int direction : directions)
                directionJson.put(direction);
            JSONObject request = new JSONObject()
                    .put("type", "prepare")
                    .put("protocol", PROTOCOL)
                    .put("runToken", runToken)
                    .put("releaseNonce", Long.toString(nonce))
                    .put("guestPlanDigest", digest)
                    .put("x", x)
                    .put("startY", startY)
                    .put("endY", endY)
                    .put("gestures", GESTURES)
                    .put("steps", STEPS)
                    .put("sampleIntervalMs", SAMPLE_INTERVAL_MS)
                    .put("gestureGapMs", GESTURE_GAP_MS)
                    .put("maxLatenessMs", MAX_LATENESS_MS)
                    .put("display", 0)
                    .put("directions", directionJson);
            writeJsonLine(writer, request);
            JSONObject prepared = readJsonLine(reader, "prepared");
            requireType(prepared, "prepared", runToken, true);
            if(!digest.equals(prepared.getString("preparedPlanDigest")) ||
                    prepared.getInt("preparedGestures") != GESTURES ||
                    prepared.getInt("preparedEvents") != EVENTS ||
                    prepared.getInt("earlyInputCount") != 0) {
                throw new AssertionError("Host prepared proof mismatch: " + prepared);
            }
            return new PreparedPlan(socket, reader, runToken, nonce, digest);
        } catch(Throwable failure) {
            try { socket.close(); } catch(Exception ignored) { }
            throw failure;
        }
    }

    private static int[] directions() {
        int[] result = new int[GESTURES];
        for(int index = 0; index < result.length; index++)
            result[index] = index == 14 || index == 29 || index == 44 ? -1 : 1;
        return result;
    }

    private static long positiveNonce() {
        long value = SystemClock.elapsedRealtimeNanos() & Long.MAX_VALUE;
        return value == 0L ? 1L : value;
    }

    private static String planDigest(int x, int startY, int endY, int[] directions)
            throws Exception {
        StringBuilder directionText = new StringBuilder();
        for(int index = 0; index < directions.length; index++) {
            if(index > 0) directionText.append(',');
            directionText.append(directions[index]);
        }
        String canonical = "v=2;x=" + x + ";startY=" + startY + ";endY=" + endY
                + ";gestures=" + GESTURES + ";steps=" + STEPS
                + ";sampleIntervalMs=" + SAMPLE_INTERVAL_MS
                + ";gestureGapMs=" + GESTURE_GAP_MS
                + ";maxLatenessMs=" + MAX_LATENESS_MS
                + ";display=0;directions=" + directionText;
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(64);
        for(byte value : digest)
            hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return hex.toString();
    }

    private static void writeJsonLine(BufferedWriter writer, JSONObject value) throws Exception {
        writer.write(value.toString());
        writer.write('\n');
        writer.flush();
    }

    private static JSONObject readJsonLine(BufferedReader reader, String expected) throws Exception {
        String line = reader.readLine();
        if(line == null)
            throw new AssertionError("Host producer closed before " + expected);
        JSONObject value = new JSONObject(line);
        if("error".equals(value.optString("type")))
            throw new AssertionError("Host producer rejected plan: " + value);
        return value;
    }

    private static void requireType(
            JSONObject value, String expected, String token, boolean requireToken) throws Exception {
        if(!expected.equals(value.optString("type")))
            throw new AssertionError("Expected host " + expected + ", got " + value);
        if(requireToken && !token.equals(value.optString("runToken")))
            throw new AssertionError("Host run token mismatch: " + value);
    }

    public static final class PreparedPlan implements Closeable {
        private final Socket socket;
        private final BufferedReader reader;
        private final String runToken;
        private final long nonce;
        private final String digest;
        private boolean released;

        private PreparedPlan(
                Socket socket, BufferedReader reader, String runToken, long nonce, String digest) {
            this.socket = socket;
            this.reader = reader;
            this.runToken = runToken;
            this.nonce = nonce;
            this.digest = digest;
        }

        public Result release(long activeCommitGuestNanos) throws Exception {
            if(released)
                throw new AssertionError("Physical-input plan released twice");
            if(activeCommitGuestNanos <= 0L)
                throw new AssertionError("Missing ACTIVE commit timestamp");
            released = true;
            long releaseSendGuestNanos = SystemClock.elapsedRealtimeNanos();
            if(releaseSendGuestNanos < activeCommitGuestNanos)
                throw new AssertionError("ACTIVE/release guest timestamp order is invalid");
            ByteBuffer frame = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN);
            frame.putInt(RELEASE_MAGIC);
            frame.putInt(PROTOCOL);
            frame.putLong(nonce);
            frame.putLong(activeCommitGuestNanos);
            frame.putLong(releaseSendGuestNanos);
            OutputStream output = socket.getOutputStream();
            output.write(frame.array());
            output.flush();

            JSONObject started = readJsonLine(reader, "started");
            requireType(started, "started", runToken, true);
            if(!digest.equals(started.getString("preparedPlanDigest")) ||
                    started.getInt("earlyInputCount") != 0 ||
                    started.getLong("releaseToFirstDownMicros") > 16_000L) {
                throw new AssertionError("Host physical release proof failed: " + started);
            }
            JSONObject completed = readJsonLine(reader, "result");
            requireType(completed, "result", runToken, true);
            Result result = new Result(completed);
            result.assertStrict();
            return result;
        }

        @Override
        public void close() {
            try { socket.close(); } catch(Exception ignored) { }
        }
    }

    public static final class Result {
        public final JSONObject json;

        private Result(JSONObject json) {
            this.json = json;
        }

        private void assertStrict() throws Exception {
            if(!json.getBoolean("ok") || json.getInt("protocol") != PROTOCOL ||
                    json.getInt("preparedGestures") != GESTURES ||
                    json.getInt("preparedEvents") != EVENTS ||
                    json.getInt("gestures") != GESTURES ||
                    json.getInt("steps") != STEPS ||
                    json.getInt("queuedEvents") != EVENTS ||
                    json.getInt("totalEvents") != EVENTS ||
                    json.getInt("sampleIntervalMs") != SAMPLE_INTERVAL_MS ||
                    json.getInt("gestureGapMs") != GESTURE_GAP_MS ||
                    json.getInt("reverseGestures") != 3 ||
                    json.getInt("earlyInputCount") != 0 ||
                    json.getInt("maxLatenessMs") > MAX_LATENESS_MS ||
                    json.getLong("maxQueueWriteMicros") > 16_000L) {
                throw new AssertionError("Host physical-input result failed: " + json);
            }
            long release = Long.parseLong(json.getString("releaseReceiveHostNs"));
            long first = Long.parseLong(json.getString("firstDownHostNs"));
            long last = Long.parseLong(json.getString("lastUpHostNs"));
            if(release <= 0L || first < release || last <= first)
                throw new AssertionError("Host physical-input timestamps failed: " + json);
        }
    }
}
