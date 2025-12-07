package com.example.mobile;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class PhoneStreamService extends WearableListenerService {

    private static final String TAG = "PhoneStreamService";
    private static final String PATH_AUDIO_CHUNK = "/audio_chunk";

    // 🔹 IMPORTANT:
    // If running on emulator with FastAPI on host:
    //   ws://10.0.2.2:8000/ws/audio/phone1
    // If running on real device with FastAPI on laptop (same WiFi):
    //   ws://<your-laptop-LAN-IP>:8000/ws/audio/phone1
    private static final String WS_URL = "ws://10.0.2.2:8000/ws";

    private static final int SAMPLE_RATE = 16000;   // Hz
    private static final int CHANNEL_COUNT = 1;     // mono
    private static final int BYTES_PER_SAMPLE = 2;  // 16-bit PCM

    private static final int BYTES_PER_SECOND =
            SAMPLE_RATE * CHANNEL_COUNT * BYTES_PER_SAMPLE;

    private static final int TARGET_SECONDS = 10;
    private static final int TARGET_BYTES = BYTES_PER_SECOND * TARGET_SECONDS;

    private final Object lock = new Object();
    private ByteArrayOutputStream currentBuffer = null;

    // WebSocket related fields
    private OkHttpClient httpClient;
    private WebSocket webSocket;
    private boolean wsConnected = false;
    private boolean wsConnecting = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "PhoneStreamService created");
        initWebSocket();  // initial attempt
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
            }

            @Override
            public void onMessage(@NonNull WebSocket ws, @NonNull String text) {
                Log.d(TAG, "📩 WS text from server: " + text);
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
            }

            @Override
            public void onClosed(@NonNull WebSocket ws, int code, @NonNull String reason) {
                Log.d(TAG, "WS CLOSED: code=" + code + ", reason=" + reason);
                wsConnected = false;
                wsConnecting = false;
            }

            @Override
            public void onFailure(@NonNull WebSocket ws, @NonNull Throwable t, Response response) {
                Log.e(TAG, "❌ WS FAILURE: " + t.getMessage(), t);
                wsConnected = false;
                wsConnecting = false;
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

        // 1) Try sending over WebSocket
        sendChunkOverWebSocket(data);

        // 2) Also buffer locally for 10s WAV
        synchronized (lock) {
            if (currentBuffer == null) {
                Log.d(TAG, "Starting new 10-second buffer");
                currentBuffer = new ByteArrayOutputStream();
            }

            try {
                currentBuffer.write(data);
            } catch (IOException e) {
                Log.e(TAG, "❌ Failed to write to buffer", e);
                return;
            }

            int size = currentBuffer.size();
            Log.d(TAG, "Buffer so far = " + size + " bytes (target=" + TARGET_BYTES + ")");

            if (size >= TARGET_BYTES) {
                Log.d(TAG, "🎯 10 seconds reached! Saving WAV locally...");

                byte[] pcmData = currentBuffer.toByteArray();
                saveRecordingToFile(pcmData);
                currentBuffer.reset();
                Log.d(TAG, "🆕 Buffer reset for next recording");
            }
        }
    }

    private void sendChunkOverWebSocket(byte[] data) {
        if (webSocket == null || !wsConnected) {
            Log.w(TAG, "⚠ WS not connected (ws=" + webSocket + ", connected=" + wsConnected + ")");
            // Trigger (re)connect once if needed
            if (!wsConnecting) {
                Log.d(TAG, "WS: triggering reconnect...");
                initWebSocket();
            }
            return;
        }

        ByteString payload = ByteString.of(data, 0, data.length);
        boolean enqueued = webSocket.send(payload);
        Log.d(TAG, "📤 Sent chunk over WS: bytes=" + data.length + ", enqueued=" + enqueued);
    }

    // ---- Local WAV saving ----

    private void saveRecordingToFile(byte[] pcmData) {
        Log.d(TAG, "Saving WAV locally, bytes=" + pcmData.length);

        File recordingsDir = new File(getFilesDir(), "recordings");
        if (!recordingsDir.exists()) {
            boolean created = recordingsDir.mkdirs();
            Log.d(TAG, "Recordings dir created: " + created + " at " + recordingsDir.getAbsolutePath());
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date());
        File outFile = new File(recordingsDir, "rec_" + timestamp + ".wav");

        Log.d(TAG, "Output file = " + outFile.getAbsolutePath());

        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            writeWavHeader(fos, pcmData.length, SAMPLE_RATE, CHANNEL_COUNT, BYTES_PER_SAMPLE * 8);
            fos.write(pcmData);
            fos.flush();
            Log.d(TAG, "✅ WAV saved successfully: " + outFile.getName());
        } catch (IOException e) {
            Log.e(TAG, "❌ Error saving WAV", e);
        }
    }

    private void writeWavHeader(FileOutputStream out,
                                int pcmDataLength,
                                int sampleRate,
                                int channels,
                                int bitsPerSample) throws IOException {

        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int totalDataLen = pcmDataLength + 36;
        int totalAudioLen = pcmDataLength;

        byte[] header = new byte[44];

        // RIFF
        header[0] = 'R';
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        writeIntLE(header, 4, totalDataLen);
        header[8]  = 'W';
        header[9]  = 'A';
        header[10] = 'V';
        header[11] = 'E';

        // fmt
        header[12] = 'f';
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';
        writeIntLE(header, 16, 16);
        writeShortLE(header, 20, (short) 1);
        writeShortLE(header, 22, (short) channels);
        writeIntLE(header, 24, sampleRate);
        writeIntLE(header, 28, byteRate);
        writeShortLE(header, 32, (short) (channels * bitsPerSample / 8));
        writeShortLE(header, 34, (short) bitsPerSample);

        // data
        header[36] = 'd';
        header[37] = 'a';
        header[38] = 't';
        header[39] = Byte.parseByte("data");
        writeIntLE(header, 40, totalAudioLen);

        out.write(header, 0, 44);
    }

    private void writeIntLE(byte[] data, int offset, int value) {
        data[offset]     = (byte) (value & 0xff);
        data[offset + 1] = (byte) ((value >> 8) & 0xff);
        data[offset + 2] = (byte) ((value >> 16) & 0xff);
        data[offset + 3] = (byte) ((value >> 24) & 0xff);
    }

    private void writeShortLE(byte[] data, int offset, short value) {
        data[offset]     = (byte) (value & 0xff);
        data[offset + 1] = (byte) ((value >> 8) & 0xff);
    }
}
