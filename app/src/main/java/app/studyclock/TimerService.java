package app.studyclock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * Two jobs:
 *   1. Keep the process alive so the WebView's timer keeps counting with the screen off.
 *   2. Show an ongoing notification whose countdown Android renders itself, which means
 *      no per-second work and no battery drain from updating it.
 */
public class TimerService extends Service {

    public static final String ACTION_START = "app.studyclock.START";
    public static final String ACTION_TOGGLE = "app.studyclock.TOGGLE";
    public static final String ACTION_SKIP = "app.studyclock.SKIP";
    public static final String ACTION_STOP = "app.studyclock.STOP";

    /* Set with the raw key rather than the API-36 setter, so this compiles on any SDK.
       Devices that do not know it ignore it and show a normal ongoing notification. */
    private static final String EXTRA_PROMOTED = "android.requestPromotedOngoing";
    public static final String EXTRA_END = "end";
    public static final String EXTRA_LABEL = "label";

    private static final String CH_ONGOING = "timer";
    private static final String CH_ALERT = "blockdone";
    private static final int ID_ONGOING = 1;
    private static final int ID_ALERT = 2;

    private static TimerService instance;

    /** How notification buttons reach the page. Same process, so a plain callback is enough. */
    public interface CommandListener { void onCommand(String cmd); }
    private static CommandListener listener;
    public static void setCommandListener(CommandListener l) { listener = l; }

    private boolean paused = false;

    private long endTime;
    private String label = "Focus";

    public static boolean isRunning() {
        return instance != null;
    }

    /** Update the notification of an already-running service, safe from the background. */
    public static void updateRunning(long end, String label) {
        TimerService s = instance;
        if (s == null) return;
        s.endTime = end;
        s.label = label;
        s.paused = false;
        s.post();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannels(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            endTime = intent.getLongExtra(EXTRA_END, System.currentTimeMillis());
            String l = intent.getStringExtra(EXTRA_LABEL);
            if (l != null) label = l;
            paused = false;
        } else if (ACTION_TOGGLE.equals(action)) {
            paused = !paused;
            if (listener != null) listener.onCommand("toggle");
        } else if (ACTION_SKIP.equals(action)) {
            if (listener != null) listener.onCommand("skip");
        } else if (ACTION_STOP.equals(action)) {
            if (listener != null) listener.onCommand("stop");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(ID_ONGOING, build());
        // Restart if Android kills us under memory pressure.
        return START_STICKY;
    }

    private void post() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(ID_ONGOING, build());
    }

    private Notification build() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_ONGOING)
                .setSmallIcon(R.drawable.ic_stat_timer)
                .setContentTitle(label)
                .setContentText("Tap to open Study Clock")
                .setContentIntent(pi)
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);

        // Android draws and ticks the countdown; nothing here runs per second.
        if (!paused && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            b.setUsesChronometer(true);
            b.setChronometerCountDown(true);
            b.setWhen(endTime);
        }
        if (paused) b.setContentText("Paused");

        b.addAction(0, paused ? "Resume" : "Pause", servicePi(ACTION_TOGGLE, 10));
        b.addAction(0, "Skip", servicePi(ACTION_SKIP, 11));
        b.addAction(0, "Stop", servicePi(ACTION_STOP, 12));

        Bundle promote = new Bundle();
        promote.putBoolean(EXTRA_PROMOTED, true);
        b.addExtras(promote);

        return b.build();
    }

    private PendingIntent servicePi(String action, int req) {
        Intent i = new Intent(this, TimerService.class);
        i.setAction(action);
        return PendingIntent.getService(this, req, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Heads-up alert when a block ends. Fires even from the lock screen. */
    public static void alertFinished(Context ctx, String title, String body) {
        createChannels(ctx);
        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                ctx, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new NotificationCompat.Builder(ctx, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat_timer)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .build();
        try {
            NotificationManagerCompat.from(ctx).notify(ID_ALERT, n);
        } catch (SecurityException ignored) {
            // Notification permission was declined. The in-app chime still plays.
        }
    }

    private static void createChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel ongoing = new NotificationChannel(
                CH_ONGOING, "Running timer", NotificationManager.IMPORTANCE_LOW);
        ongoing.setDescription("The countdown shown while a block is running.");
        ongoing.setShowBadge(false);
        ongoing.setSound(null, null);
        nm.createNotificationChannel(ongoing);

        NotificationChannel alert = new NotificationChannel(
                CH_ALERT, "Block finished", NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("Alerts you when a focus block or break ends.");
        alert.enableVibration(true);
        nm.createNotificationChannel(alert);
    }

    @Override
    public void onDestroy() {
        instance = null;
        listener = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
