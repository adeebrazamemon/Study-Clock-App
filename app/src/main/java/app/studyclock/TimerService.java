package app.studyclock;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.util.Collections;

/**
 * Owns the running block rather than only drawing it.
 *
 * The page is still where a session is decided, but once a block starts the
 * service holds the end time, the paused state, and one block of lookahead, so
 * it can roll over on its own. That matters because the WebView's timers are
 * throttled when the app is backgrounded, so the page cannot be relied on to
 * notice that a block ended.
 *
 * The page stays authoritative. When it wakes it recomputes and calls
 * startTimer, which overwrites whatever the service decided in the meantime.
 */
public class TimerService extends Service {

    public static final String ACTION_START = "app.studyclock.START";
    public static final String ACTION_TOGGLE = "app.studyclock.TOGGLE";
    public static final String ACTION_SKIP = "app.studyclock.SKIP";
    public static final String ACTION_STOP = "app.studyclock.STOP";
    public static final String ACTION_BLOCK_END = "app.studyclock.BLOCK_END";

    public static final String EXTRA_END = "end";
    public static final String EXTRA_LABEL = "label";
    public static final String EXTRA_NEXT_LABEL = "nextLabel";
    public static final String EXTRA_NEXT_MS = "nextMs";
    public static final String EXTRA_AUTO = "auto";
    public static final String EXTRA_TOTAL_MS = "totalMs";

    private static final String CH_ONGOING = "timer";
    private static final String CH_ALERT = "blockdone";
    private static final int ID_ONGOING = 1;
    private static final int ID_ALERT = 2;

    /** Wait this long past the end before acting, to let a live page get there first. */
    private static final long GRACE_MS = 1500L;

    private static TimerService instance;

    /** How notification buttons reach the page. Same process, so a plain callback is enough. */
    public interface CommandListener { void onCommand(String cmd); }
    private static CommandListener listener;
    public static void setCommandListener(CommandListener l) { listener = l; }

    private boolean paused = false;
    /** Milliseconds left, frozen, while paused. Zero means the block is spent. */
    private long pausedRemaining = 0L;

    private long endTime;
    private String label = "Focus";

    /** One block of lookahead, handed over by the page when a block starts. */
    private String nextLabel = null;
    private long nextMs = 0L;
    private boolean auto = false;

    /** Full length of the block in progress, for the notification's progress bar. */
    private long totalMs = 0L;

    public static boolean isRunning() {
        return instance != null;
    }

    /** Update an already-running service, safe from the background. */
    public static void updateRunning(long end, String label,
                                     String nextLabel, long nextMs, boolean auto, long totalMs) {
        TimerService s = instance;
        if (s == null) return;
        s.endTime = end;
        s.label = label;
        s.nextLabel = nextLabel;
        s.nextMs = nextMs;
        s.auto = auto;
        s.totalMs = totalMs;
        s.paused = false;
        s.pausedRemaining = 0L;
        s.scheduleEnd();
        s.post();
    }

    /** Freeze the countdown with this much left, keeping the notification up. */
    public static void pause(long remainingMs, String label) {
        TimerService s = instance;
        if (s == null) return;
        s.paused = true;
        s.pausedRemaining = Math.max(0L, remainingMs);
        if (label != null) s.label = label;
        s.cancelEnd();
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
            nextLabel = intent.getStringExtra(EXTRA_NEXT_LABEL);
            nextMs = intent.getLongExtra(EXTRA_NEXT_MS, 0L);
            auto = intent.getBooleanExtra(EXTRA_AUTO, false);
            totalMs = intent.getLongExtra(EXTRA_TOTAL_MS, 0L);
            paused = false;
            pausedRemaining = 0L;
            scheduleEnd();

        } else if (ACTION_TOGGLE.equals(action)) {
            // Act locally first, so the button still works when the page is gone.
            if (paused) {
                endTime = System.currentTimeMillis() + pausedRemaining;
                paused = false;
                pausedRemaining = 0L;
                scheduleEnd();
            } else {
                paused = true;
                pausedRemaining = Math.max(0L, endTime - System.currentTimeMillis());
                cancelEnd();
            }
            if (listener != null) listener.onCommand("toggle");

        } else if (ACTION_SKIP.equals(action)) {
            if (listener != null) listener.onCommand("skip");

        } else if (ACTION_BLOCK_END.equals(action)) {
            onBlockEnd();

        } else if (ACTION_STOP.equals(action)) {
            if (listener != null) listener.onCommand("stop");
            cancelEnd();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(ID_ONGOING, build());
        // Restart if Android kills us under memory pressure.
        return START_STICKY;
    }

    /**
     * The block ran out while nobody was watching. If the page was alive it has
     * already pushed the end time forward, and the second check sends us home.
     */
    private void onBlockEnd() {
        if (paused) return;
        if (System.currentTimeMillis() < endTime) return;

        alertFinished(this, label + " done",
                nextLabel != null ? "Next: " + nextLabel : "Session finished.");

        if (nextLabel == null || nextMs <= 0L) {
            // Nothing queued, so stop counting rather than run into negative time.
            paused = true;
            pausedRemaining = 0L;
            return;
        }

        String queuedLabel = nextLabel;
        long queuedMs = nextMs;
        // Only one block of lookahead. The page refills it when it wakes.
        nextLabel = null;
        nextMs = 0L;
        label = queuedLabel;
        // queuedMs is the queued block's full length (focus/short/long break all
        // differ), so it doubles as the new totalMs for the progress bar.
        totalMs = queuedMs;

        if (auto) {
            endTime = System.currentTimeMillis() + queuedMs;
            paused = false;
            scheduleEnd();
        } else {
            paused = true;
            pausedRemaining = queuedMs;
        }
    }

    private void scheduleEnd() {
        AlarmManager am = getSystemService(AlarmManager.class);
        if (am == null) return;
        long at = endTime + GRACE_MS;
        PendingIntent pi = servicePi(ACTION_BLOCK_END, 13);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            // Exact alarms not permitted here. Inexact still fires, just later.
            am.set(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    private void cancelEnd() {
        AlarmManager am = getSystemService(AlarmManager.class);
        if (am != null) am.cancel(servicePi(ACTION_BLOCK_END, 13));
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
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                // Asks for the API 36 Live Update / expanded card treatment.
                // The compat class no-ops on older Android versions, which just
                // show a normal ongoing notification.
                .setRequestPromotedOngoing(true);

        // Android draws and ticks the countdown; nothing here runs per second.
        if (!paused && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            b.setUsesChronometer(true);
            b.setChronometerCountDown(true);
            b.setWhen(endTime);
        }
        if (paused) {
            b.setShowWhen(false);
            b.setContentText(pausedRemaining > 0
                    ? "Paused at " + mmss(pausedRemaining)
                    : "Block finished");
        }

        if (totalMs > 0) {
            long remainingNow = paused ? pausedRemaining
                    : Math.max(0L, endTime - System.currentTimeMillis());
            int totalSec = (int) Math.max(1L, totalMs / 1000L);
            int elapsedSec = (int) Math.min(totalSec,
                    Math.max(0L, (totalMs - remainingNow) / 1000L));
            b.setStyle(new NotificationCompat.ProgressStyle()
                    .setStyledByProgress(false)
                    .setProgressSegments(Collections.singletonList(
                            new NotificationCompat.ProgressStyle.Segment(totalSec)
                                    .setColor(getColor(R.color.ink))))
                    .setProgress(elapsedSec));
        }

        b.addAction(0, paused ? "Resume" : "Pause", servicePi(ACTION_TOGGLE, 10));
        b.addAction(0, "Skip", servicePi(ACTION_SKIP, 11));
        b.addAction(0, "Stop", servicePi(ACTION_STOP, 12));

        return b.build();
    }

    static String mmss(long ms) {
        long secs = Math.max(0L, ms) / 1000L;
        long m = secs / 60L, s = secs % 60L;
        return m + ":" + (s < 10 ? "0" : "") + s;
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
        cancelEnd();
        instance = null;
        listener = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
