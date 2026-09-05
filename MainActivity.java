package app.studyclock;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

public class MainActivity extends AppCompatActivity {

    private WebView web;
    private boolean immersive = false;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);

        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "AndroidTimer");
        web.loadUrl("file:///android_asset/index.html");

        askForNotificationPermission();

        // Buttons on the ongoing notification call straight into the page.
        TimerService.setCommandListener(new TimerService.CommandListener() {
            @Override public void onCommand(String cmd) { callWeb(cmd); }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (immersive) {
                    // Leave full screen rather than the app.
                    callWeb("exitfs");
                } else {
                    moveTaskToBack(true);
                }
            }
        });
    }

    /** Run one of the page's commands: toggle, skip, exitfs. */
    private void callWeb(final String cmd) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (web != null) {
                    web.evaluateJavascript(
                            "window.__nativeCmd && window.__nativeCmd('" + cmd + "');", null);
                }
            }
        });
    }

    private void askForNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }
    }

    /** Hide or restore the status and navigation bars.
     *  Deliberately not called setImmersive: Activity already declares a public
     *  method of that name, and a private override will not compile. */
    private void applyImmersive(boolean on) {
        immersive = on;
        View decor = getWindow().getDecorView();
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), decor);
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            c.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            c.hide(WindowInsetsCompat.Type.systemBars());
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            c.show(WindowInsetsCompat.Type.systemBars());
        }
    }

    private class Bridge {

        /**
         * nextLabel and nextMs are one block of lookahead, so the service can roll
         * over on its own when the page is throttled in the background.
         */
        @JavascriptInterface
        public void startTimer(final String endTime, final String label,
                               final String nextLabel, final String nextMs,
                               final boolean auto) {
            final long end;
            try { end = Long.parseLong(endTime); } catch (Exception e) { return; }
            long parsedNext;
            try { parsedNext = Long.parseLong(nextMs); } catch (Exception e) { parsedNext = 0L; }
            final long next = parsedNext;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (TimerService.isRunning()) {
                        TimerService.updateRunning(end, label, nextLabel, next, auto);
                    } else {
                        Intent i = new Intent(MainActivity.this, TimerService.class);
                        i.setAction(TimerService.ACTION_START);
                        i.putExtra(TimerService.EXTRA_END, end);
                        i.putExtra(TimerService.EXTRA_LABEL, label);
                        i.putExtra(TimerService.EXTRA_NEXT_LABEL, nextLabel);
                        i.putExtra(TimerService.EXTRA_NEXT_MS, next);
                        i.putExtra(TimerService.EXTRA_AUTO, auto);
                        ContextCompat.startForegroundService(MainActivity.this, i);
                    }
                }
            });
        }

        /** Freeze the notification instead of tearing the service down. */
        @JavascriptInterface
        public void pauseTimer(final String remainingMs, final String label) {
            long parsed;
            try { parsed = Long.parseLong(remainingMs); } catch (Exception e) { parsed = 0L; }
            final long remaining = parsed;
            runOnUiThread(new Runnable() {
                @Override public void run() { TimerService.pause(remaining, label); }
            });
        }

        @JavascriptInterface
        public void stopTimer() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    stopService(new Intent(MainActivity.this, TimerService.class));
                }
            });
        }

        @JavascriptInterface
        public void blockDone(final String title, final String body) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    TimerService.alertFinished(MainActivity.this, title, body);
                }
            });
        }

        /** Called by the page when full screen is entered or left. */
        @JavascriptInterface
        public void setFullscreen(final boolean on) {
            runOnUiThread(new Runnable() {
                @Override public void run() { applyImmersive(on); }
            });
        }

        @JavascriptInterface
        public boolean isNative() {
            return true;
        }
    }
}
