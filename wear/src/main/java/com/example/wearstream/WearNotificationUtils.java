package com.example.wearstream;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

import androidx.core.app.NotificationCompat;

public class WearNotificationUtils {

    private static final String CHANNEL_ID = "rec_channel";

    public static Notification createNotification(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Audio Recording",
                NotificationManager.IMPORTANCE_LOW
        );

        nm.createNotificationChannel(channel);

        return new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setContentTitle("Recording Audio")
                .setContentText("Streaming to phone…")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build();
    }
}
