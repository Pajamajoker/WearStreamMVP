package com.example.mobile;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

public class PhoneStreamService extends WearableListenerService {

    private static final String TAG = "PhoneStreamService";
    private static final String PATH_AUDIO_CHUNK = "/audio_chunk";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "PhoneStreamService created");
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        Log.d(TAG, "onMessageReceived: path=" + messageEvent.getPath());

        if (PATH_AUDIO_CHUNK.equals(messageEvent.getPath())) {
            byte[] data = messageEvent.getData();
            Log.d(TAG, "Received audio chunk of size " + data.length +
                    " bytes. Pretending to POST to backend (HTTP 200).");
            // TODO: later actually POST to backend
        } else {
            super.onMessageReceived(messageEvent);
        }
    }
}
