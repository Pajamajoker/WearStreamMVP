package com.example.mobile;  // ← keep your package name

import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import android.app.NotificationChannel;
import android.app.NotificationManager;

public class PhoneStreamService extends WearableListenerService {

    private static final String TAG = "PhoneStreamService";

    private static final String PATH_AUDIO_CHUNK = "/audio_chunk";
    private static final String PATH_ALERT = "/alert";

    // WebSocket URL: emulator -> host
    private static final String WS_URL = "ws://10.0.2.2:8000/ws";

    // ---- Status broadcast constants ----
    public static final String ACTION_STATUS = "com.example.mobile.WS_STATUS";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_CONNECTED = "connected";
    public static final String EXTRA_BYTES_SENT = "bytes_sent";

    // Phone notification channel for alerts
    private static final String ALERT_CHANNEL_ID = "server_alerts";

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
        initAlertNotificationChannel();
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

                try {
                    JSONObject root = new JSONObject(text);
                    String type = root.optString("type", "");
                    if ("alert".equals(type)) {
                        JSONObject event = root.optJSONObject("event");
                        if (event != null) {
                            String level = event.optString("level", "alert").toUpperCase();
                            String msg   = event.optString("message", "");

                            // Parse rolling scores for nicer summary
                            JSONObject rolling = event.optJSONObject("rolling");
                            double alarm    = rolling != null ? rolling.optDouble("alarm", 0)    : 0;
                            double gunshot  = rolling != null ? rolling.optDouble("gunshot", 0)  : 0;
                            double explosion= rolling != null ? rolling.optDouble("explosion",0) : 0;
                            double vocal    = rolling != null ? rolling.optDouble("vocal", 0)    : 0;

                            String summary = String.format(
                                    "alarm=%.2f | gunshot=%.2f | explosion=%.2f | vocal=%.2f",
                                    alarm, gunshot, explosion, vocal
                            );

                            Log.d(TAG, "⚠️ ALERT from server: level=" + level + ", msg=" + msg);

                            showPhoneAlertNotification(level, msg, summary);
                            forwardAlertToWatch(root);
                        }
                    }


                } catch (Exception e) {
                    Log.e(TAG, "Failed to parse WS alert JSON", e);
                }
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

    private void handleWebSocketText(String text) {
        try {
            JSONObject root = new JSONObject(text);
            String type = root.optString("type", "");

            if ("alert".equals(type)) {
                JSONObject event = root.optJSONObject("event");
                if (event != null) {
                    handleAlertFromServer(event);
                } else {
                    Log.w(TAG, "WS alert with no 'event' field");
                }
            } else {
                Log.d(TAG, "WS message type=" + type + " (ignored)");
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse WS JSON", e);
        }
    }

    // ---- Handle alert coming from backend ----

    private void handleAlertFromServer(JSONObject event) {
        String level = event.optString("level", "unknown");
        String msg = event.optString("message", "(no message)");
        String summary = event.optString("summary", "(no summary)");

        Log.d(TAG, "⚠️ ALERT from server: level=" + level + ", msg=" + msg);

        // 1) Update status for MainActivity
        broadcastStatus("ALERT: " + msg, wsConnected);

        // 2) Show notification on phone
        showPhoneAlertNotification(level, msg, summary);

        // 3) Forward to watch
        forwardAlertToWatch(event);
    }

    private void initAlertNotificationChannel() {
        NotificationChannelCompat channel = new NotificationChannelCompat.Builder(
                ALERT_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
        )
                .setName("Emergency Alerts")
                .setDescription("Alerts from audio emergency backend")
                .build();

        NotificationManagerCompat.from(this).createNotificationChannel(channel);
    }

    private void showPhoneAlertNotification(String level, String msg, String summary) {
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    ALERT_CHANNEL_ID,
                    "LASER Alerts",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Urgent LASER emergency / warning alerts");
            nm.createNotificationChannel(channel);
        }

        String title = "LASER " + level + " alert";

        NotificationCompat.BigTextStyle big =
                new NotificationCompat.BigTextStyle()
                        .setBigContentTitle(title)
                        .bigText(msg + "\n" + summary);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(msg)
                .setStyle(big)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        nm.notify(1001, builder.build());
    }



    private void forwardAlertToWatch(JSONObject event) {
        try {
            JSONObject wrapper = new JSONObject();
            wrapper.put("type", "alert");
            wrapper.put("event", event);

            final byte[] payload = wrapper.toString().getBytes(StandardCharsets.UTF_8);

            Wearable.getNodeClient(this).getConnectedNodes()
                    .addOnSuccessListener(nodes -> {
                        Log.d(TAG, "[ALERT] Forwarding alert to watch, nodes=" + nodes.size());
                        if (nodes.isEmpty()) {
                            Log.w(TAG, "[ALERT] No connected nodes, cannot forward alert");
                            return;
                        }

                        for (Node node : nodes) {
                            Log.d(TAG, "[ALERT] Sending /alert to " + node.getDisplayName()
                                    + " (" + node.getId() + ")");
                            Wearable.getMessageClient(this)
                                    .sendMessage(node.getId(), PATH_ALERT, payload)
                                    .addOnSuccessListener(unused ->
                                            Log.d(TAG, "✅ [ALERT] Alert sent to watch"))
                                    .addOnFailureListener(e ->
                                            Log.e(TAG, "❌ [ALERT] Failed to send alert", e));
                        }
                    })
                    .addOnFailureListener(e ->
                            Log.e(TAG, "❌ [ALERT] getConnectedNodes FAILED", e));

        } catch (JSONException e) {
            Log.e(TAG, "forwardAlertToWatch: JSON error", e);
        }
    }

    // ---- Wear message handling (from watch mic → phone → backend) ----

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
