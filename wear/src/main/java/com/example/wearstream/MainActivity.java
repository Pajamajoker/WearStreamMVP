package com.example.wearstream;

import android.Manifest;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private Button startBtn, stopBtn;

    private final ActivityResultLauncher<String> audioPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted) startRecordingService();
                    });

    // 🔔 notification permission launcher (Wear OS 4 / Android 13+)
    private final ActivityResultLauncher<String> notifPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        // nothing special to do, just needed once
                    });
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        startBtn = findViewById(R.id.btnStart);
        stopBtn = findViewById(R.id.btnStop);

        startBtn.setOnClickListener(v ->
                audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO));

        stopBtn.setOnClickListener(v ->
                stopService(new Intent(this, AudioRecordService.class)));

        // 🔔 Request notification permission on Wear OS 4+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private void startRecordingService() {
        Intent i = new Intent(this, AudioRecordService.class);
        startForegroundService(i);
    }
}
