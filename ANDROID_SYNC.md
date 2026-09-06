# Android Google Sign-In + Sync — implementation handoff

Goal: make the Google sign-in + Firestore sync that already works on the web
version also work inside the Android app. The web uses
`fbAuth.signInWithPopup(GoogleAuthProvider)` (index.html `signIn()`), which an
Android WebView blocks. Everything else (Firestore read/write, the `state`
sync payload) already runs in the page's JS and will work as-is once auth
succeeds.

## Approach (chosen: minimal native, reuse the JS SDK)

Do NOT add the native Firebase/Firestore SDK or the `google-services` Gradle
plugin (that needs google-services.json and duplicates working JS code).
Instead:

1. Native gets a Google **ID token** via Credential Manager
   (`androidx.credentials` + `com.google.android.libraries.identity.googleid`).
2. Native passes that token into the WebView JS.
3. JS calls
   `fbAuth.signInWithCredential(firebase.auth.GoogleAuthProvider.credential(idToken))`.
4. From there the existing `pullOnce`/`pushSync` Firestore code takes over
   unchanged.

## What only the user can provide (blockers)

1. **Register the Android app in the `study-clock-app` Firebase project**
   - Package name: `app.studyclock`
   - Add the debug keystore's **SHA-1** (see keystore note below).
2. **Web OAuth client ID** — from Firebase console → Authentication → Google
   provider → "Web SDK configuration" (a `...apps.googleusercontent.com`
   string). Credential Manager needs this as the *server* client id. Paste it
   into `WEB_CLIENT_ID` in MainActivity (placeholder added in code).
3. Google is already enabled as a sign-in provider (web uses it) — no action.

## Stable SHA-1 / keystore (IMPORTANT)

The CI (`.github/workflows/build.yml`, `gradle assembleDebug`) currently uses
whatever debug keystore the runner auto-generates, so its SHA-1 changes every
build and Google Sign-In would break. Fix before wiring auth:

- Commit a fixed debug keystore at `app/debug.keystore` and point a
  `signingConfigs.debug` at it in `app/build.gradle`, used by the debug build
  type. Then register THAT keystore's SHA-1 in Firebase.
- Generate it with:
  `keytool -genkey -v -keystore app/debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"`
- Get its SHA-1 with:
  `keytool -list -v -keystore app/debug.keystore -storepass android -alias androiddebugkey` (the `SHA1:` line).

## Code changes to make (next session)

1. `app/build.gradle` deps:
   `implementation "androidx.credentials:credentials:1.3.0"`,
   `implementation "androidx.credentials:credentials-play-services-auth:1.3.0"`,
   `implementation "com.google.android.libraries.identity.googleid:googleid:1.1.1"`.
   (Also add the `signingConfigs.debug` block above.)
2. `MainActivity.java`:
   - `WEB_CLIENT_ID` constant (user fills).
   - `@JavascriptInterface public void signInGoogle()` on the `Bridge` class:
     runs `CredentialManager.getCredential` with a `GetGoogleIdOption`
     (`setServerClientId(WEB_CLIENT_ID)`), on success extract the ID token and
     `web.evaluateJavascript("window.__googleToken('" + idToken + "')", null)`.
     Also a `signOutGoogle()` that clears the credential state.
3. `index.html`:
   - In `signIn()`: `if(NATIVE){ window.AndroidTimer.signInGoogle(); return; }`
     before the popup call.
   - Add `window.__googleToken = function(idToken){ fbAuth.signInWithCredential(
     firebase.auth.GoogleAuthProvider.credential(idToken))
     .catch(function(){ note("Sign-in failed."); }); };`
   - In `renderSyncUI()`, remove the `NATIVE` "use the web version" gate so the
     Sign-in button shows in the app.
   - `signOutSync()`: also call `window.AndroidTimer.signOutGoogle()` when NATIVE.
4. Bump version, test on device, confirm two-way sync app <-> web.

## Config already in hand (index.html)

`firebaseConfig`: apiKey AIzaSyCleiRCg-pmRQLW4xU0120KO8eAFwfnk0g,
authDomain study-clock-app.firebaseapp.com, projectId study-clock-app.
