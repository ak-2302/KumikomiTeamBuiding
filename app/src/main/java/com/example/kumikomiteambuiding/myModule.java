package com.example.kumikomiteambuiding;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.CombinedVibration;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationManagerCompat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provides the sound, vibration, and notification operations used by the app.
 */
public final class myModule {
    private static final String TAG = "myModule";
    private static final int DEFAULT_BEEP_DURATION_MS = 200;
    private static final long DEFAULT_VIBRATION_DURATION_MS = 500L;
    private static final int STRONG_VIBRATION_AMPLITUDE = 255;
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return vibrateWithManager(context, durationMs);
        }

        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) {
            Log.w(TAG, "Vibrator is not available");
            return false;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(createStrongVibration(durationMs), createAlarmAudioAttributes());
            } else {
                vibrator.vibrate(durationMs, createAlarmAudioAttributes());
            }
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "VIBRATE permission is not available", e);
            return false;
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to start vibration", e);
            return false;
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private static boolean vibrateWithManager(Context context, long durationMs) {
        VibratorManager vibratorManager =
                (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
        if (vibratorManager == null) {
            Log.w(TAG, "VibratorManager is not available");
            return false;
        }

        Vibrator vibrator = vibratorManager.getDefaultVibrator();
        if (vibrator == null || !vibrator.hasVibrator()) {
            Log.w(TAG, "Default vibrator is not available");
            return false;
        }

        try {
            vibratorManager.vibrate(
                    CombinedVibration.createParallel(createStrongVibration(durationMs)),
                    new VibrationAttributes.Builder()
                            .setUsage(VibrationAttributes.USAGE_ALARM)
                            .build()
            );
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "VIBRATE permission is not available", e);
            return false;
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to start vibration", e);
            return false;
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private static VibrationEffect createStrongVibration(long durationMs) {
        return VibrationEffect.createOneShot(durationMs, STRONG_VIBRATION_AMPLITUDE);
    }

    private static AudioAttributes createAlarmAudioAttributes() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
    }

    public static MediaControlResult toggleMediaPlayback(Context context) {
        MediaController controller = getActiveMediaController(context);
        if (controller == null) {
            return mediaControllerFailure(context);
        }

        PlaybackState playbackState = controller.getPlaybackState();
        int state = playbackState != null
                ? playbackState.getState()
                : PlaybackState.STATE_NONE;
        if (state == PlaybackState.STATE_PLAYING
                || state == PlaybackState.STATE_BUFFERING
                || state == PlaybackState.STATE_CONNECTING) {
            controller.getTransportControls().pause();
        } else {
            controller.getTransportControls().play();
        }
        return MediaControlResult.SUCCESS;
    }

    public static MediaControlResult skipToNextMedia(Context context) {
        MediaController controller = getActiveMediaController(context);
        if (controller == null) {
            return mediaControllerFailure(context);
        }
        controller.getTransportControls().skipToNext();
        return MediaControlResult.SUCCESS;
    }

    public static MediaControlResult skipToPreviousMedia(Context context) {
        MediaController controller = getActiveMediaController(context);
        if (controller == null) {
            return mediaControllerFailure(context);
        }
        controller.getTransportControls().skipToPrevious();
        return MediaControlResult.SUCCESS;
    }

    public static boolean hasMediaControlAccess(Context context) {
        requireContext(context);
        return NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.getPackageName());
    }

    private static MediaController getActiveMediaController(Context context) {
        requireContext(context);
        if (!hasMediaControlAccess(context)) {
            return null;
        }

        MediaSessionManager sessionManager =
                (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (sessionManager == null) {
            return null;
        }

        try {
            ComponentName listenerComponent = new ComponentName(
                    context,
                    MediaNotificationListenerService.class
            );
            List<MediaController> controllers =
                    sessionManager.getActiveSessions(listenerComponent);
            if (controllers.isEmpty()) {
                return null;
            }

            for (MediaController controller : controllers) {
                if ("com.spotify.music".equals(controller.getPackageName())) {
                    return controller;
                }
            }
            return controllers.get(0);
        } catch (SecurityException e) {
            Log.w(TAG, "Notification listener access is not available", e);
            return null;
        }
    }

    private static MediaControlResult mediaControllerFailure(Context context) {
        return hasMediaControlAccess(context)
                ? MediaControlResult.NO_ACTIVE_SESSION
                : MediaControlResult.ACCESS_NOT_GRANTED;
    }

    public enum MediaControlResult {
        SUCCESS,
        ACCESS_NOT_GRANTED,
        NO_ACTIVE_SESSION
    }

    public static boolean notification(Context context, String message) {
        requireContext(context);
        return notification(context, context.getString(R.string.app_name), message);
    }

    public static boolean notification(Context context, String title, String message) {
        return notification(context, title, message, MainActivity.class);
    }

    public static boolean notification(
            Context context,
            String title,
            String message,
            Class<?> targetActivity
    ) {
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

        if (targetActivity == null) {
            throw new IllegalArgumentException("targetActivity must not be null");
        }

        Intent intent = new Intent(context, targetActivity)
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
