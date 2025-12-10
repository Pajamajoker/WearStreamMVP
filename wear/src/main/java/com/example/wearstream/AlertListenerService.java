package com.example.wearstream;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

public class AlertListenerService extends WearableListenerService {

    private static final String TAG = "AlertListenerService";
    private static final String PATH_ALERT = "/alert";
    private static final String CHANNEL_ID = "watch_alerts";

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "LASER Alerts",
                    NotificationManager.IMPORTANCE_HIGH
            );
            ch.setDescription("Emergency alerts from LASER backend");
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        String path = messageEvent.getPath();
        if (!PATH_ALERT.equals(path)) {
            super.onMessageReceived(messageEvent);
            return;
        }

        String json = new String(messageEvent.getData(), StandardCharsets.UTF_8);
        Log.d(TAG, "📩 ALERT payload from phone: " + json);

        try {
            JSONObject root = new JSONObject(json);
            JSONObject event = root.optJSONObject("event");
            if (event == null) {
                Log.w(TAG, "No 'event' in alert JSON");
                return;
            }

            String level = event.optString("level", "alert").toUpperCase();
            String msg   = event.optString("message", "");

            JSONObject rolling = event.optJSONObject("rolling");
            double alarm = 0, gunshot = 0, explosion = 0, vocal = 0;
            if (rolling != null) {
                alarm     = rolling.optDouble("alarm", 0);
                gunshot   = rolling.optDouble("gunshot", 0);
                explosion = rolling.optDouble("explosion", 0);
                vocal     = rolling.optDouble("vocal", 0);
            }

            String summary = String.format(
                    "Looks like you are in an Emergency Situation! SOS Message will be triggered!"
            );

            showWatchAlertNotification(level, msg, summary);

        } catch (Exception e) {
            Log.e(TAG, "Failed to parse alert JSON on watch", e);
        }
    }

    private void showWatchAlertNotification(String level, String msg, String summary) {
        NotificationManagerCompat nm = NotificationManagerCompat.from(this);

        String title = "LASER SOS alert";

        NotificationCompat.BigTextStyle style = new NotificationCompat.BigTextStyle()
                .setBigContentTitle(title)
                .bigText(msg + "\n" + summary);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)      // what shows in the compact view
                .setContentText(msg)         // first line
                .setStyle(style)             // expanded text (msg + summary)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        nm.notify(2001, builder.build());
    }
}
