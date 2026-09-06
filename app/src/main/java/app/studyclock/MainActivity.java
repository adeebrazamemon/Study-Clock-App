package app.studyclock;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
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

        // Edge-to-edge for the whole app, not just the in-page "Full screen"
        // clock mode: without this, hiding the system bars below just leaves
        // their reserved space blank instead of handing it to the WebView,
        // so the page never actually gets the room back.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);

        web.setWebViewClient(new WebViewClient());
        // Without this, WebView silently no-ops window.confirm/alert/prompt --
        // no dialog, no error, confirm() just returns false immediately. That
        // made deleting a task with logged time (which asks for confirmation)
        // look like the button did nothing at all.
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "AndroidTimer");
        web.loadUrl("file:///android_asset/index.html");

        askForNotificationPermission();
        // The whole app runs edge-to-edge, not just the in-page "Full
        // screen" clock mode -- the status bar and nav bar were otherwise
        // always visible during ordinary use, quietly eating into the
        // height every layout calculation on the page assumed it had.
        hideSystemBars();

        // Buttons on the ongoing notification call straight into the page.
        TimerService.setCommandListener(new TimerService.CommandListener() {
            @Override public void onCommand(String cmd) { callWeb(cmd); }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (immersive) {
                    // Leave full screen rather than the app.
                    callWeb("exitfs");
                    return;
                }
                // A <dialog> (Settings, Categories, ...) is a page-side concept the
                // system back button knows nothing about. Ask the page to close
                // whichever one is open; only background the app if there wasn't one.
                web.evaluateJavascript(
                        "window.__closeTopDialog ? window.__closeTopDialog() : false",
                        new ValueCallback<String>() {
                            @Override public void onReceiveValue(String value) {
                                if (!"true".equals(value)) moveTaskToBack(true);
                            }
                        });
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

    /** System bars hidden (swipe from an edge to peek them back temporarily)
     *  for ordinary use of the whole app -- separate from applyImmersive()
     *  below, which additionally keeps the screen on and is tied to the
     *  in-page "Full screen" clock mode specifically (including the back
     *  button's exitfs handling). This one has no such side effects, so
     *  it's safe to call any time the bars might have crept back. */
    private void hideSystemBars() {
        View decor = getWindow().getDecorView();
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), decor);
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        c.hide(WindowInsetsCompat.Type.systemBars());
    }

    /** Android tends to let the system bars creep back after the window
     *  loses and regains focus (backgrounding, a permission dialog, the
     *  keyboard) -- re-hide them whenever focus returns. */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    /** The manifest lists orientation/screenSize/etc. under
     *  android:configChanges, which means Android hands changes like a
     *  rotation to this method instead of destroying and recreating the
     *  Activity -- onCreate() (and its one-time hideSystemBars() call)
     *  never runs again. Some devices re-show the system bars on exactly
     *  that kind of change, so without this they'd stay shown from the
     *  first rotation onward for the rest of the session. */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        hideSystemBars();
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
            // The app's baseline is edge-to-edge (bars hidden), so leaving the
            // in-page Full screen clock must NOT c.show() the bars -- that was
            // exactly why the status/nav bars reappeared for good after one
            // Full-screen -> Exit round trip and never tucked away again.
            hideSystemBars();
        }
    }

    private class Bridge {

        /**
         * nextLabel and nextMs are one block of lookahead, so the service can roll
         * over on its own when the page is throttled in the background. totalMs is
         * the current block's full length, so the notification's progress bar can
         * show elapsed/total rather than just a countdown.
         */
        @JavascriptInterface
        public void startTimer(final String endTime, final String label,
                               final String nextLabel, final String nextMs,
                               final boolean auto, final String totalMs) {
            final long end;
            try { end = Long.parseLong(endTime); } catch (Exception e) { return; }
            long parsedNext;
            try { parsedNext = Long.parseLong(nextMs); } catch (Exception e) { parsedNext = 0L; }
            final long next = parsedNext;
            long parsedTotal;
            try { parsedTotal = Long.parseLong(totalMs); } catch (Exception e) { parsedTotal = 0L; }
            final long total = parsedTotal;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (TimerService.isRunning()) {
                        TimerService.updateRunning(end, label, nextLabel, next, auto, total);
                    } else {
                        Intent i = new Intent(MainActivity.this, TimerService.class);
                        i.setAction(TimerService.ACTION_START);
                        i.putExtra(TimerService.EXTRA_END, end);
                        i.putExtra(TimerService.EXTRA_LABEL, label);
                        i.putExtra(TimerService.EXTRA_NEXT_LABEL, nextLabel);
                        i.putExtra(TimerService.EXTRA_NEXT_MS, next);
                        i.putExtra(TimerService.EXTRA_AUTO, auto);
                        i.putExtra(TimerService.EXTRA_TOTAL_MS, total);
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

        /** So the page can adopt the truth when it wakes up possibly stale
            about a Pause/Skip pressed on the notification while it wasn't
            around to hear about it. Null (as a string) if nothing is running. */
        @JavascriptInterface
        public String getServiceState() {
            return TimerService.snapshotJson();
        }
    }
}
