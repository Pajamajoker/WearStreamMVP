package com.example.wearstream;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.util.List;

public class AudioRecordService extends Service {

    private static final String TAG = "AudioRecordService";
    private static final String CHANNEL_ID = "audio_record_channel";
    private static final int NOTIF_ID = 1;
    private static final String PATH_AUDIO_CHUNK = "/audio_chunk";

    private AudioRecord audioRecord;
    private Thread recordingThread;
    private boolean isRecording = false;

    // audio config – keep it simple
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Idle"));

        int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                minBuf
        );
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isRecording) {
            startRecording();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopRecording();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "WearStream Recording",
                            NotificationManager.IMPORTANCE_LOW
                    );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("WearStream")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .build();
    }

    private void startRecording() {
        if (audioRecord == null) return;

        isRecording = true;
        audioRecord.startRecording();
        Log.d(TAG, "Recording started");

        Notification notif = buildNotification("Recording audio…");
        startForeground(NOTIF_ID, notif);

        recordingThread = new Thread(() -> {
            int minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            byte[] buffer = new byte[minBuf];

            while (isRecording) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    // copy exact bytes into a fresh array
                    byte[] chunk = new byte[read];
                    System.arraycopy(buffer, 0, chunk, 0, read);

                    // 🔹 send this chunk to the paired phone
                    sendChunkToPhone(chunk);
                }
            }

            Log.d(TAG, "Recording loop exited");
        }, "AudioRecordThread");

        recordingThread.start();
    }

    private void stopRecording() {
        isRecording = false;
        if (recordingThread != null) {
            try {
                recordingThread.join();
            } catch (InterruptedException ignored) {
            }
            recordingThread = null;
        }
        if (audioRecord != null) {
            audioRecord.stop();
        }
        Log.d(TAG, "Recording stopped");
        stopForeground(true);
    }

    // -------- Wear Data Layer: send /audio_chunk to phone --------

    private void sendChunkToPhone(byte[] data) {
        Log.d(TAG, "Preparing to send chunk of size: " + data.length);

        Wearable.getNodeClient(this).getConnectedNodes()
                .addOnSuccessListener(nodes -> {
                    Log.d(TAG, "Connected nodes found: " + nodes.size());
                    if (nodes.isEmpty()) {
                        Log.e(TAG, "❌ No nodes connected! Phone not reachable.");
                    }

                    for (Node node : nodes) {
                        Log.d(TAG, "Sending to node: " + node.getDisplayName() +
                                " (" + node.getId() + ")");

                        Wearable.getMessageClient(AudioRecordService.this)
                                .sendMessage(node.getId(), PATH_AUDIO_CHUNK, data)
                                .addOnSuccessListener(aVoid ->
                                        Log.d(TAG, "✅ Chunk sent (" + data.length + " bytes)"))
                                .addOnFailureListener(e ->
                                        Log.e(TAG, "❌ Failed to send chunk", e));
                    }
                })
                .addOnFailureListener(e ->
                        Log.e(TAG, "❌ getConnectedNodes FAILED", e));
    }

}
