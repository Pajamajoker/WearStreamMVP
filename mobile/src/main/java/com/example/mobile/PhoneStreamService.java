package com.example.mobile;

import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class PhoneStreamService extends WearableListenerService {

    private static final String TAG = "PhoneStreamService";
    private static final String PATH_AUDIO_CHUNK = "/audio_chunk";

    // WebSocket URL: emulator -> host
    private static final String WS_URL = "ws://10.0.2.2:8000/ws";

    // ---- Status broadcast constants ----
    public static final String ACTION_STATUS = "com.example.mobile.WS_STATUS";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_CONNECTED = "connected";
    public static final String EXTRA_BYTES_SENT = "bytes_sent";

    // For debug only (not saving locally)
    private final Object lock = new Object();
    private ByteArrayOutputStream currentBuffer = null;

    // WebSocket related fields
    private OkHttpClient httpClient;
    private WebSocket webSocket;
    private boolean wsConnected = false;
    private boolean wsConnecting = false;
    private long totalBytesSent = 0L;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "PhoneStreamService created");
        initWebSocket();  // initial attempt
        broadcastStatus("Starting (connecting…)", false);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "PhoneStreamService destroyed");

        if (webSocket != null) {
            Log.d(TAG, "Closing WebSocket");
            webSocket.close(1000, "Service destroyed");
            webSocket = null;
        }
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient = null;
        }

        broadcastStatus("Service stopped", false);
    }

    // ---- WebSocket setup ----

    private void initWebSocket() {
        if (wsConnecting) {
            Log.d(TAG, "WS: already connecting, skipping initWebSocket()");
            return;
        }
        wsConnecting = true;

        if (httpClient == null) {
            httpClient = new OkHttpClient();
        }

        Request request = new Request.Builder()
                .url(WS_URL)
                .build();

        Log.d(TAG, "WS: Connecting to " + WS_URL);

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket ws, @NonNull Response response) {
                Log.d(TAG, "✅ WS OPEN");
                wsConnected = true;
                wsConnecting = false;
                broadcastStatus("Connected to backend", true);
            }

            @Override
            public void onMessage(@NonNull WebSocket ws, @NonNull String text) {
                Log.d(TAG, "📩 WS text from server: " + text);
                // e.g., "saved_10s_clip"
            }

            @Override
            public void onMessage(@NonNull WebSocket ws, @NonNull ByteString bytes) {
                Log.d(TAG, "📩 WS binary from server, size=" + bytes.size());
            }

            @Override
            public void onClosing(@NonNull WebSocket ws, int code, @NonNull String reason) {
                Log.d(TAG, "WS CLOSING: code=" + code + ", reason=" + reason);
                ws.close(1000, null);
                wsConnected = false;
                wsConnecting = false;
                broadcastStatus("Closing connection…", false);
            }

            @Override
            public void onClosed(@NonNull WebSocket ws, int code, @NonNull String reason) {
                Log.d(TAG, "WS CLOSED: code=" + code + ", reason=" + reason);
                wsConnected = false;
                wsConnecting = false;
                broadcastStatus("Closed connection", false);
            }

            @Override
            public void onFailure(@NonNull WebSocket ws, @NonNull Throwable t, Response response) {
                Log.e(TAG, "❌ WS FAILURE: " + t.getMessage(), t);
                wsConnected = false;
                wsConnecting = false;
                broadcastStatus("Backend connection failed", false);
            }
        });
    }

    // ---- Wear message handling ----

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        String path = messageEvent.getPath();
        Log.d(TAG, "📩 onMessageReceived: path=" + path);

        if (!PATH_AUDIO_CHUNK.equals(path)) {
            super.onMessageReceived(messageEvent);
            return;
        }

        byte[] data = messageEvent.getData();
        Log.d(TAG, "📥 Received chunk size = " + data.length + " bytes");

        // 1) Send to backend over WebSocket
        sendChunkOverWebSocket(data);

        // 2) Debug-only local buffer (no WAV saving)
        synchronized (lock) {
            if (currentBuffer == null) {
                currentBuffer = new ByteArrayOutputStream();
            }

            try {
                currentBuffer.write(data);
            } catch (IOException e) {
                Log.e(TAG, "❌ Failed to write to buffer", e);
                return;
            }

            int size = currentBuffer.size();
            Log.d(TAG, "Buffer so far (debug only) = " + size + " bytes");
        }
    }

    private void sendChunkOverWebSocket(byte[] data) {
        if (webSocket == null || !wsConnected) {
            Log.w(TAG, "⚠ WS not connected (ws=" + webSocket + ", connected=" + wsConnected + ")");
            // Trigger (re)connect once if needed
            if (!wsConnecting) {
                Log.d(TAG, "WS: triggering reconnect...");
                initWebSocket();
                broadcastStatus("Reconnecting to backend…", false);
            }
            return;
        }

        ByteString payload = ByteString.of(data, 0, data.length);
        boolean enqueued = webSocket.send(payload);
        if (enqueued) {
            totalBytesSent += data.length;
        }
        Log.d(TAG, "📤 Sent chunk over WS: bytes=" + data.length + ", enqueued=" + enqueued);
        broadcastStatus(wsConnected ? "Streaming to backend" : "Not connected", wsConnected);
    }

    // ---- Broadcast status to MainActivity ----

    private void broadcastStatus(String status, boolean connected) {
        Intent intent = new Intent(ACTION_STATUS);
        intent.putExtra(EXTRA_STATUS, status);
        intent.putExtra(EXTRA_CONNECTED, connected);
        intent.putExtra(EXTRA_BYTES_SENT, totalBytesSent);
        sendBroadcast(intent);
    }
}
