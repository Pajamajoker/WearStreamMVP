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

public class PhoneStreamService extends WearableListenerService {

    private static final String TAG = "PhoneStreamService";
    private static final String PATH_AUDIO_CHUNK = "/audio_chunk";

    private static final int SAMPLE_RATE = 16000;   // Hz
    private static final int CHANNEL_COUNT = 1;     // mono
    private static final int BYTES_PER_SAMPLE = 2;  // 16-bit PCM

    private static final int BYTES_PER_SECOND =
            SAMPLE_RATE * CHANNEL_COUNT * BYTES_PER_SAMPLE;

    private static final int TARGET_SECONDS = 10;
    private static final int TARGET_BYTES = BYTES_PER_SECOND * TARGET_SECONDS;

    private final Object lock = new Object();
    private ByteArrayOutputStream currentBuffer = null;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "PhoneStreamService created");
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        String path = messageEvent.getPath();
        Log.d(TAG, "onMessageReceived: path=" + path);

        if (!PATH_AUDIO_CHUNK.equals(path)) {
            super.onMessageReceived(messageEvent);
            return;
        }

        byte[] data = messageEvent.getData();
        Log.d(TAG, "Received chunk of size " + data.length + " bytes");

        synchronized (lock) {
            if (currentBuffer == null) {
                currentBuffer = new ByteArrayOutputStream();
            }

            try {
                currentBuffer.write(data);
            } catch (IOException e) {
                Log.e(TAG, "Failed to append audio chunk", e);
                return;
            }

            int size = currentBuffer.size();
            Log.d(TAG, "Current buffer size = " + size + " bytes");

            if (size >= TARGET_BYTES) {
                byte[] pcmData = currentBuffer.toByteArray();
                saveRecordingToFile(pcmData);
                currentBuffer.reset();
            }
        }
    }

    private void saveRecordingToFile(byte[] pcmData) {
        File recordingsDir = new File(getFilesDir(), "recordings");
        if (!recordingsDir.exists() && !recordingsDir.mkdirs()) {
            Log.e(TAG, "Failed to create recordings dir: " + recordingsDir.getAbsolutePath());
            return;
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date());
        File outFile = new File(recordingsDir, "rec_" + timestamp + ".wav");

        Log.d(TAG, "Saving recording to: " + outFile.getAbsolutePath());

        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            writeWavHeader(fos, pcmData.length, SAMPLE_RATE, CHANNEL_COUNT, BYTES_PER_SAMPLE * 8);
            fos.write(pcmData);
            fos.flush();
            Log.d(TAG, "Recording saved successfully: " + outFile.getName());
        } catch (IOException e) {
            Log.e(TAG, "Error saving WAV file", e);
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
        header[39] = 'a';
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
