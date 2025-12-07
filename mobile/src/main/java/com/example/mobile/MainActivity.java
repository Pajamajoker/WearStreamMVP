package com.example.mobile;

import androidx.appcompat.app.AppCompatActivity;

import android.media.MediaPlayer;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private ListView listView;
    private ArrayAdapter<String> adapter;
    private final List<File> recordingFiles = new ArrayList<>();
    private MediaPlayer mediaPlayer = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        listView = findViewById(R.id.listRecordings);

        adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_list_item_1,
                new ArrayList<>()
        );

        listView.setAdapter(adapter);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                File file = recordingFiles.get(position);
                playRecording(file);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadRecordings();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    private void loadRecordings() {
        recordingFiles.clear();
        adapter.clear();

        File recordingsDir = new File(getFilesDir(), "recordings");
        if (!recordingsDir.exists()) {
            Log.d(TAG, "No recordings dir yet: " + recordingsDir.getAbsolutePath());
            adapter.notifyDataSetChanged();
            return;
        }

        File[] files = recordingsDir.listFiles((dir, name) -> name.endsWith(".wav"));
        if (files == null || files.length == 0) {
            Log.d(TAG, "No .wav recordings found");
            adapter.notifyDataSetChanged();
            return;
        }

        // Sort newest first
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File o1, File o2) {
                return Long.compare(o2.lastModified(), o1.lastModified());
            }
        });

        List<String> names = new ArrayList<>();
        for (File f : files) {
            recordingFiles.add(f);
            names.add(f.getName());
        }

        adapter.addAll(names);
        adapter.notifyDataSetChanged();
    }

    private void playRecording(File file) {
        Log.d(TAG, "Playing file: " + file.getAbsolutePath());

        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }

        mediaPlayer = new MediaPlayer();
        try {
            mediaPlayer.setDataSource(file.getAbsolutePath());
            mediaPlayer.prepare();
            mediaPlayer.start();
            Toast.makeText(this, "Playing: " + file.getName(), Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Log.e(TAG, "Error playing recording", e);
            Toast.makeText(this, "Failed to play recording", Toast.LENGTH_SHORT).show();
        }
    }
}
