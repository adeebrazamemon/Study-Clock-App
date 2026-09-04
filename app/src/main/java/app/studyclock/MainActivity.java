package app.studyclock;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private WebView web;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);              // localStorage lives here
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);  // so the end-of-block chime plays
        s.setSupportZoom(false);

        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "AndroidTimer");
        web.loadUrl("file:///android_asset/index.html");

        askForNotificationPermission();

        // Back button hides the app instead of killing it, so the timer keeps running.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                moveTaskToBack(true);
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

    /** Methods here are callable from JavaScript as window.AndroidTimer.*  */
    private class Bridge {

        /**
         * Called when a block starts, or when its end time changes.
         * endTime is epoch millis. The notification counts down to it by itself,
         * so this does not need calling every second.
         */
        @JavascriptInterface
        public void startTimer(final String endTime, final String label) {
            final long end;
            try { end = Long.parseLong(endTime); } catch (Exception e) { return; }
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (TimerService.isRunning()) {
                        TimerService.updateRunning(end, label);
                    } else {
                        Intent i = new Intent(MainActivity.this, TimerService.class);
                        i.setAction(TimerService.ACTION_START);
                        i.putExtra(TimerService.EXTRA_END, end);
                        i.putExtra(TimerService.EXTRA_LABEL, label);
                        ContextCompat.startForegroundService(MainActivity.this, i);
                    }
                }
            });
        }

        /** Called on pause, reset, or when nothing is running. */
        @JavascriptInterface
        public void stopTimer() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    stopService(new Intent(MainActivity.this, TimerService.class));
                }
            });
        }

        /** Called the moment a block finishes, so you get an alert even from the lock screen. */
        @JavascriptInterface
        public void blockDone(final String title, final String body) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    TimerService.alertFinished(MainActivity.this, title, body);
                }
            });
        }

        /** Lets the web app know it is running inside the Android build. */
        @JavascriptInterface
        public boolean isNative() {
            return true;
        }
    }
}
