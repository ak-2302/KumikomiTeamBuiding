package com.example.kumikomiteambuiding;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Receives commands from the Raspberry Pi through a small HTTP server.
 */
public final class HttpAnalyzer {
    public static final int DEFAULT_PORT = 8080;

    private static final String TAG = "HttpAnalyzer";
    private static final int MAX_LINE_BYTES = 8 * 1024;
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final Context context;
    private final int port;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ExecutorService executor;
    private ServerSocket serverSocket;

    public HttpAnalyzer(Context context, Listener listener) {
        this(context, DEFAULT_PORT, listener);
    }

    public HttpAnalyzer(Context context, int port, Listener listener) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }

        this.context = context.getApplicationContext();
        this.port = port;
        this.listener = listener;
    }

    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        executor = Executors.newCachedThreadPool();
        executor.execute(this::acceptConnections);
    }

    public synchronized void stop() {
        running.set(false);

        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                Log.w(TAG, "Failed to close the HTTP server", e);
            }
            serverSocket = null;
        }

        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getPort() {
        return port;
    }

    private void acceptConnections() {
        try (ServerSocket socket = new ServerSocket(port)) {
            synchronized (this) {
                serverSocket = socket;
            }
            if (!running.get()) {
                return;
            }
            notifyServerStarted();

            while (running.get()) {
                Socket client = socket.accept();
                ExecutorService currentExecutor;
                synchronized (this) {
                    currentExecutor = executor;
                }
                if (currentExecutor != null) {
                    currentExecutor.execute(() -> handleClient(client));
                } else {
                    client.close();
                }
            }
        } catch (SocketException e) {
            if (running.get()) {
                notifyServerError(e);
            }
        } catch (IOException e) {
            if (running.get()) {
                notifyServerError(e);
            }
        } finally {
            running.set(false);
            synchronized (this) {
                serverSocket = null;
            }
        }
    }

    private void handleClient(Socket socket) {
        try (Socket client = socket) {
            client.setSoTimeout(5000);

            BufferedInputStream input = new BufferedInputStream(client.getInputStream());
            BufferedOutputStream output = new BufferedOutputStream(client.getOutputStream());
            try {
                HttpRequest request = readRequest(input);
                HttpResponse response = route(request);
                writeResponse(output, response);
            } catch (BadRequestException e) {
                writeResponse(output, HttpResponse.error(e.statusCode, e.getMessage()));
            } catch (JSONException e) {
                Log.w(TAG, "Failed to process JSON in an HTTP request", e);
                writeResponse(output, HttpResponse.error(400, "invalid JSON"));
            } catch (RuntimeException e) {
                Log.e(TAG, "Unexpected error while processing an HTTP request", e);
                writeResponse(output, HttpResponse.error(500, "internal server error"));
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to process an HTTP connection", e);
        }
    }

    private HttpResponse route(HttpRequest request) throws JSONException, BadRequestException {
        if (!"POST".equals(request.method)) {
            return HttpResponse.error(405, "POST method required");
        }

        JSONObject body = parseJsonBody(request.body);

        switch (request.path) {
            case "/api/data":
                JSONObject data = parseDataPayload(body);
                mainHandler.post(() -> {
                    if (listener != null) {
                        listener.onDataReceived(data);
                    }
                });
                return HttpResponse.ok("data received");

            case "/api/beep":
                int beepDuration = optionalPositiveInt(body, "durationMs", 200);
                mainHandler.post(() -> myModule.beep(beepDuration));
                return HttpResponse.ok("beep accepted");

            case "/api/vibrate":
                long vibrationDuration = optionalPositiveLong(body, "durationMs", 500L);
                if (!myModule.vibrate(context, vibrationDuration)) {
                    return HttpResponse.error(409, "vibrator is not available");
                }
                return HttpResponse.ok("vibration accepted");

            case "/api/notification":
                String title = body.optString("title", context.getString(R.string.app_name)).trim();
                String message = requiredString(body, "message");
                if (title.isEmpty()) {
                    throw new BadRequestException(400, "title must not be empty");
                }
                mainHandler.post(() -> {
                    boolean displayed = myModule.notification(context, title, message);
                    if (!displayed) {
                        Log.w(TAG, "Notification was not displayed; check notification permission");
                    }
                });
                return HttpResponse.ok("notification accepted");

            default:
                return HttpResponse.error(404, "endpoint not found");
        }
    }

    private static JSONObject parseDataPayload(JSONObject body)
            throws JSONException, BadRequestException {
        String type = requiredString(body, "type");
        if (!body.has("value")) {
            throw new BadRequestException(400, "value is required");
        }

        JSONObject data = new JSONObject();
        data.put("type", type);
        data.put("value", body.get("value"));
        return data;
    }

    private static JSONObject parseJsonBody(String body) throws BadRequestException {
        if (body == null || body.trim().isEmpty()) {
            return new JSONObject();
        }

        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            throw new BadRequestException(400, "body must be a JSON object");
        }
    }

    private static HttpRequest readRequest(BufferedInputStream input)
            throws IOException, BadRequestException {
        String requestLine = readLine(input);
        String[] requestParts = requestLine.split(" ");
        if (requestParts.length != 3 || !requestParts[2].startsWith("HTTP/1.")) {
            throw new BadRequestException(400, "invalid request line");
        }

        Map<String, String> headers = new HashMap<>();
        String line;
        while (!(line = readLine(input)).isEmpty()) {
            int separator = line.indexOf(':');
            if (separator <= 0) {
                throw new BadRequestException(400, "invalid header");
            }
            headers.put(
                    line.substring(0, separator).trim().toLowerCase(Locale.US),
                    line.substring(separator + 1).trim());
        }

        int contentLength = parseContentLength(headers.get("content-length"));
        byte[] bodyBytes = readExactly(input, contentLength);
        String path = requestParts[1].split("\\?", 2)[0];
        return new HttpRequest(
                requestParts[0].toUpperCase(Locale.US),
                path,
                new String(bodyBytes, StandardCharsets.UTF_8));
    }

    private static String readLine(BufferedInputStream input)
            throws IOException, BadRequestException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int previous = -1;
        int current;

        while ((current = input.read()) != -1) {
            if (previous == '\r' && current == '\n') {
                byte[] bytes = buffer.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.US_ASCII);
            }
            buffer.write(current);
            if (buffer.size() > MAX_LINE_BYTES) {
                throw new BadRequestException(431, "request line or header is too large");
            }
            previous = current;
        }

        throw new EOFException("connection closed before request was complete");
    }

    private static int parseContentLength(String value) throws BadRequestException {
        if (value == null) {
            return 0;
        }

        try {
            int length = Integer.parseInt(value);
            if (length < 0 || length > MAX_BODY_BYTES) {
                throw new BadRequestException(413, "request body is too large");
            }
            return length;
        } catch (NumberFormatException e) {
            throw new BadRequestException(400, "invalid Content-Length");
        }
    }

    private static byte[] readExactly(BufferedInputStream input, int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(bytes, offset, length - offset);
            if (read == -1) {
                throw new EOFException("connection closed before body was complete");
            }
            offset += read;
        }
        return bytes;
    }

    private static int optionalPositiveInt(JSONObject body, String key, int defaultValue)
            throws BadRequestException {
        long value = optionalPositiveLong(body, key, defaultValue);
        if (value > Integer.MAX_VALUE) {
            throw new BadRequestException(400, key + " is too large");
        }
        return (int) value;
    }

    private static long optionalPositiveLong(JSONObject body, String key, long defaultValue)
            throws BadRequestException {
        if (!body.has(key)) {
            return defaultValue;
        }

        try {
            long value = body.getLong(key);
            if (value <= 0) {
                throw new BadRequestException(400, key + " must be greater than 0");
            }
            return value;
        } catch (JSONException e) {
            throw new BadRequestException(400, key + " must be an integer");
        }
    }

    private static String requiredString(JSONObject body, String key) throws BadRequestException {
        Object value = body.opt(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new BadRequestException(400, key + " must be a non-empty string");
        }
        return ((String) value).trim();
    }

    private static void writeResponse(BufferedOutputStream output, HttpResponse response)
            throws IOException {
        byte[] body = response.body.toString().getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + response.statusCode + " " + statusText(response.statusCode)
                + "\r\nContent-Type: application/json; charset=utf-8"
                + "\r\nContent-Length: " + body.length
                + "\r\nConnection: close\r\n\r\n";

        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private static String statusText(int statusCode) {
        switch (statusCode) {
            case 200:
                return "OK";
            case 400:
                return "Bad Request";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 409:
                return "Conflict";
            case 411:
                return "Length Required";
            case 413:
                return "Payload Too Large";
            case 431:
                return "Request Header Fields Too Large";
            default:
                return "Internal Server Error";
        }
    }

    private void notifyServerStarted() {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onServerStarted(port);
            }
        });
    }

    private void notifyServerError(Exception error) {
        Log.e(TAG, "HTTP server stopped because of an error", error);
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onServerError(error);
            }
        });
    }

    public interface Listener {
        void onDataReceived(Object data);

        default void onServerStarted(int port) {
        }

        default void onServerError(Exception error) {
        }
    }

    private static final class HttpRequest {
        private final String method;
        private final String path;
        private final String body;

        private HttpRequest(String method, String path, String body) {
            this.method = method;
            this.path = path;
            this.body = body;
        }
    }

    private static final class HttpResponse {
        private final int statusCode;
        private final JSONObject body;

        private HttpResponse(int statusCode, JSONObject body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        private static HttpResponse ok(String message) {
            return create(200, true, message);
        }

        private static HttpResponse error(int statusCode, String message) {
            return create(statusCode, false, message);
        }

        private static HttpResponse create(int statusCode, boolean success, String message) {
            JSONObject body = new JSONObject();
            try {
                body.put("success", success);
                body.put("message", message);
            } catch (JSONException e) {
                throw new IllegalStateException("Failed to create JSON response", e);
            }
            return new HttpResponse(statusCode, body);
        }
    }

    private static final class BadRequestException extends Exception {
        private final int statusCode;

        private BadRequestException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }
}
