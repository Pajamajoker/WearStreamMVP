package com.example.wearstream;

import android.Manifest;
import android.content.Intent;
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
    }

    private void startRecordingService() {
        Intent i = new Intent(this, AudioRecordService.class);
        startForegroundService(i);
    }
}
