# Study Clock — Android

The same app as the web version, wrapped natively so the timer survives the
screen going off and shows a live countdown in your notification shade.

You do not need Android Studio. GitHub builds the APK for you.

## Getting the APK

1. Make a free account at github.com if you don't have one.
2. Create a new repository. Private is fine. Do not tick "add a README".
3. On the empty repo page use **uploading an existing file**, then drag in
   everything from this folder. Keep the folder structure. Commit.
4. Go to the **Actions** tab. A run called "Build APK" starts by itself.
   Give it four or five minutes.
5. Open the finished run, scroll to **Artifacts**, download
   `study-clock-apk.zip`, unzip it. Inside is `app-debug.apk`.

If Actions says workflows are disabled, click the green button on that tab to
enable them, then Run workflow.

## Installing it

Move the APK to your phone and tap it. Android will ask whether to allow
installs from this source. Allow it, install, done.

It is signed with the standard debug key. That is fine for sideloading. It only
matters if you ever put it on the Play Store, which needs a release key.

## Then do this

**Settings → Apps → Study Clock → Battery → Unrestricted.**

Without it, Xiaomi, Oppo, Vivo and Samsung will eventually kill the app and your
timer stops. This one setting is the difference between the app working and not.

## What the native part does

- A foreground service runs while a block is going. Android keeps the process
  alive, so the countdown carries on with the screen off or the app swiped away.
- The ongoing notification shows the mode and the current category, with a
  countdown that Android renders and ticks itself. Nothing runs per second, so
  the battery cost is negligible.
- When a block ends you get a heads-up notification with sound and vibration,
  visible from the lock screen.
- The back button hides the app rather than closing it, so the timer keeps going.

Your data lives in the WebView's storage inside the app. **Back up data** and
**Restore from backup** work exactly as on the web version, and the backup file
is interchangeable between the two.

## Making changes later

The whole app is `app/src/main/assets/index.html`. Edit it, push, and Actions
builds a new APK. Bump `versionCode` and `versionName` in `app/build.gradle`
when you do, otherwise Android may refuse to install over the old copy.

## Files

| Path | What it is |
|---|---|
| `app/src/main/assets/index.html` | The app itself |
| `app/src/main/java/app/studyclock/MainActivity.java` | WebView host and the JavaScript bridge |
| `app/src/main/java/app/studyclock/TimerService.java` | Foreground service and notifications |
| `app/src/main/AndroidManifest.xml` | Permissions and components |
| `.github/workflows/build.yml` | The cloud build |
