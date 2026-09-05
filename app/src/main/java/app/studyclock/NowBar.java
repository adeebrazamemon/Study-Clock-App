package app.studyclock;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.widget.RemoteViews;

import java.lang.reflect.Method;

/**
 * Samsung Now Bar support for the ongoing timer notification.
 *
 * Two mechanisms, both set as plain extras keys on the same notification, so
 * nothing here needs compileSdk 36:
 *
 *   1. Samsung's private Live Notifications keys (One UI 7 and up). One UI
 *      honours them only if the posting package is on Samsung's whitelist.
 *      That whitelist is keyed on package name alone, which is what the
 *      "nowbar" build flavour spoofs. Everywhere else they are ignored.
 *
 *   2. Android 16 Live Updates, requested by TimerService with the
 *      android.requestPromotedOngoing extra. That path is public and needs no
 *      spoofing, but on One UI it currently depends on Developer options >
 *      "Live notifications for all apps".
 */
final class NowBar {

    private static final String TAG = "NowBar";
    private static final String K = "android.ongoingActivityNoti.";

    private NowBar() {}

    /**
     * Samsung Live Notifications / Now Bar extras.
     *
     * Merge with builder.addExtras(). Never setExtras(), which would drop the
     * platform's own keys along with the chronometer this notification relies on.
     *
     * @param primary     Now Bar headline, e.g. "Organic Chemistry"
     * @param secondary   second line, e.g. "Focus" or "Paused", may be null
     * @param chipColor   ARGB for the status bar chip, also tints the drawer card
     * @param chipIconRes drawable for the chip, 0 to fall back to the small icon
     * @param remainingMs milliseconds left, 0 or less for no countdown
     * @param hasActions  true if the notification carries action buttons
     */
    static Bundle extras(Context ctx,
                         String primary,
                         String secondary,
                         int chipColor,
                         int chipIconRes,
                         long remainingMs,
                         boolean hasActions) {

        Bundle b = new Bundle();

        // Must always be 1. Without it One UI ignores every other key here.
        b.putInt(K + "style", 1);

        b.putString(K + "primaryInfo", primary);
        if (secondary != null) {
            b.putString(K + "secondaryInfo", secondary);
        }

        b.putInt(K + "chipBgColor", chipColor);
        b.putString(K + "chipExpandedText", "Study Clock");
        if (chipIconRes != 0) {
            b.putParcelable(K + "chipIcon", Icon.createWithResource(ctx, chipIconRes));
        }

        // The lock screen card can carry different text from the drawer. Same here.
        b.putString(K + "nowbarPrimaryInfo", primary);
        if (secondary != null) {
            b.putString(K + "nowbarSecondaryInfo", secondary);
        }

        // Surface Pause / Skip / Stop inside the live notification.
        if (hasActions) {
            b.putInt(K + "actionType", 1);
            b.putInt(K + "actionPrimarySet", 0);
        }

        // Ticking countdown. The system renders and ticks the Chronometer itself,
        // so this keeps counting on the lock screen with no per-second work here.
        if (remainingMs > 0) {
            RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.nowbar_chronometer);
            rv.setChronometer(R.id.nowbar_chrono,
                    SystemClock.elapsedRealtime() + remainingMs, null, true);
            rv.setBoolean(R.id.nowbar_chrono, "setCountDown", true);

            b.putParcelable(K + "chronometerRemoteView", rv);
            b.putInt(K + "chronometerRemoteViewPosition", 1);
            b.putString(K + "chronometerRemoteViewTag", "study_clock_chrono");
            b.putInt(K + "nowbarChronometerPosition", 1);
        }

        return b;
    }

    /** "24m" or "45s" for the collapsed status bar chip. Keep it under 7 characters. */
    static String shortText(long remainingMs) {
        long secs = remainingMs / 1000L;
        if (secs < 60L) return secs + "s";
        return (secs / 60L) + "m";
    }

    /**
     * Logs why the Android 16 path did or did not promote. Reflection, because
     * both methods are API 36 and this builds against 35.
     *
     * hasPromotableCharacteristics false means the notification itself is
     * ineligible. canPostPromotedNotifications false means the notification is
     * fine but the user or the OEM has the feature switched off, which on One UI
     * is the "Live notifications for all apps" developer toggle.
     *
     * adb logcat -s NowBar
     */
    static void logEligibility(Context ctx, Notification n) {
        if (Build.VERSION.SDK_INT < 36) return;
        String promotable = "unknown";
        String allowed = "unknown";
        try {
            Method m = Notification.class.getMethod("hasPromotableCharacteristics");
            promotable = String.valueOf(m.invoke(n));
        } catch (Throwable ignored) {
        }
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) {
                Method m = NotificationManager.class.getMethod("canPostPromotedNotifications");
                allowed = String.valueOf(m.invoke(nm));
            }
        } catch (Throwable ignored) {
        }
        Log.d(TAG, "promotable=" + promotable + " allowedByUser=" + allowed);
    }
}
