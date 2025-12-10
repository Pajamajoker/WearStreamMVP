package com.example.mobile;

import androidx.appcompat.app.AppCompatActivity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.content.pm.PackageManager;
import android.os.Build;
import android.Manifest;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQ_NOTIFICATIONS = 1001;
    private View statusDot;
    private TextView tvStatus;
    private TextView tvDetails;

    private final BroadcastReceiver wsStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!PhoneStreamService.ACTION_STATUS.equals(intent.getAction())) {
                return;
            }

            boolean connected = intent.getBooleanExtra(PhoneStreamService.EXTRA_CONNECTED, false);
            String status = intent.getStringExtra(PhoneStreamService.EXTRA_STATUS);
            long bytesSent = intent.getLongExtra(PhoneStreamService.EXTRA_BYTES_SENT, 0L);

            Log.d(TAG, "WS status update: " + status + ", connected=" + connected + ", bytes=" + bytesSent);
            updateUi(connected, status, bytesSent);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusDot = findViewById(R.id.statusDot);
        tvStatus = findViewById(R.id.tvStatus);
        tvDetails = findViewById(R.id.tvDetails);

        // Ask for notification permission on Android 13+
        requestNotificationPermissionIfNeeded();

        // Initial state
        setStatusDotColor(0xFFFF9800); // orange - starting
        tvStatus.setText("Starting…");
        tvDetails.setText("Waiting for service and backend connection");
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIFICATIONS
                );
            }
        }
    }

    // optional: just to log the result
    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS) {
            if (grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "POST_NOTIFICATIONS granted");
            } else {
                Log.d(TAG, "POST_NOTIFICATIONS denied");
            }
        }
    }
    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(PhoneStreamService.ACTION_STATUS);

        // For Android 13+ / targetSdk >= 33, must specify exported flag
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            // Only our app will send these broadcasts → NOT_EXPORTED is safest
            registerReceiver(wsStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            // Older behavior
            registerReceiver(wsStatusReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(wsStatusReceiver);
    }

    private void updateUi(boolean connected, String status, long bytesSent) {
        if (status == null) status = connected ? "Connected" : "Disconnected";

        tvStatus.setText(status);

        if (connected) {
            setStatusDotColor(0xFF4CAF50); // green
            tvDetails.setText("Streaming audio to backend\nTotal bytes sent: " + bytesSent);
        } else {
            setStatusDotColor(0xFFF44336); // red
            tvDetails.setText("Not connected to backend\nBytes sent so far: " + bytesSent);
        }
    }

    private void setStatusDotColor(int color) {
        // Make the dot circular with given color
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        statusDot.setBackground(bg);
    }
}
