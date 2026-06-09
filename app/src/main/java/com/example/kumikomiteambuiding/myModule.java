package com.example.kumikomiteambuiding;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provides the sound, vibration, and notification operations used by the app.
 */
public final class myModule {
    private static final int DEFAULT_BEEP_DURATION_MS = 200;
    private static final long DEFAULT_VIBRATION_DURATION_MS = 500L;
    private static final String NOTIFICATION_CHANNEL_ID = "focus_alerts";
    private static final AtomicInteger NEXT_NOTIFICATION_ID = new AtomicInteger(1);

    private myModule() {
        // Utility class.
    }

    public static void beep() {
        beep(DEFAULT_BEEP_DURATION_MS);
    }

    public static void beep(int durationMs) {
        if (durationMs <= 0) {
            throw new IllegalArgumentException("durationMs must be greater than 0");
        }

        ToneGenerator toneGenerator =
                new ToneGenerator(AudioManager.STREAM_NOTIFICATION, ToneGenerator.MAX_VOLUME);
        if (!toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, durationMs)) {
            toneGenerator.release();
            return;
        }

        new Handler(Looper.getMainLooper()).postDelayed(
                toneGenerator::release,
                durationMs + 100L
        );
    }

    public static boolean vibrate(Context context) {
        return vibrate(context, DEFAULT_VIBRATION_DURATION_MS);
    }

    public static boolean vibrate(Context context, long durationMs) {
        requireContext(context);
        if (durationMs <= 0) {
            throw new IllegalArgumentException("durationMs must be greater than 0");
        }

        Vibrator vibrator;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vibratorManager =
                    (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = vibratorManager == null ? null : vibratorManager.getDefaultVibrator();
        } else {
            vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        }

        if (vibrator == null || !vibrator.hasVibrator()) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
            );
        } else {
            vibrator.vibrate(durationMs);
        }
        return true;
    }

    public static boolean notification(Context context, String message) {
        requireContext(context);
        return notification(context, context.getString(R.string.app_name), message);
    }

    public static boolean notification(Context context, String title, String message) {
        requireContext(context);
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("title must not be empty");
        }
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("message must not be empty");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null) {
            return false;
        }

        createNotificationChannel(notificationManager);

        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(context, NOTIFICATION_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(context);
        }

        Notification notification = builder
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(message)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();

        notificationManager.notify(NEXT_NOTIFICATION_ID.getAndIncrement(), notification);
        return true;
    }

    private static void createNotificationChannel(NotificationManager notificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        if (notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "集中アラート",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription("集中状態に関する通知");
        notificationManager.createNotificationChannel(channel);
    }

    private static void requireContext(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
    }
}
