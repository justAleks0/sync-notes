# Sync Notes

A notes app that syncs between the website, your computer, and your phone. All three
clients talk to the same Firebase project (`sync-notes-c252b`) and read the same
documents, so an edit on one device shows up on the others within a second.

## Data model

```
users/{uid}/notes/{noteId}
  title      string
  body       string
  createdAt  timestamp
  updatedAt  timestamp
```

Notes are nested under their owner rather than in a top-level collection. That makes
ownership a path check in the security rules, and lets the list query order by
`updatedAt` without needing a composite index.

## Projects

| Folder              | What it is                    | Stack                          |
| ------------------- | ----------------------------- | ------------------------------ |
| `Website/`          | The web app — the core UI     | Vite + React + TypeScript      |
| `Computer Software/`| Windows desktop app           | Electron, loads the same build |
| `Android App/`      | Phone app                     | Kotlin + Jetpack Compose       |

The desktop app is deliberately not a second UI. It serves `Website/dist` over a
loopback HTTP server and loads that, so there is one interface to maintain.
(It serves over `http://localhost` rather than opening the files directly because
Firebase Auth only permits sign-in from an authorized domain — `localhost` is
authorized by default, `file://` has no origin at all.)

## Running it

Website — dev server on http://localhost:5173:

```bash
npm install --prefix Website && npm run dev --prefix Website
```

Desktop — builds the website first, then opens the Electron window:

```bash
npm start --prefix "Computer Software"
```

Packaged Windows installer, written to `Computer Software/dist`:

```bash
npm run dist --prefix "Computer Software"
```

Android — debug APK at `Android App/app/build/outputs/apk/debug`:

```bash
cd "Android App" && ./gradlew :app:assembleDebug
```

## Deploying the website

Vercel, with **Root Directory** set to `Website`. Framework preset is Vite; build
command and output directory are detected automatically. No environment variables
are needed — the Firebase web config is public by design (it identifies the project;
it does not grant access, the security rules do).

After the first deploy, add the Vercel domain under
**Firebase Console → Authentication → Settings → Authorized domains**, otherwise
Google sign-in will be rejected on the live site.

## Security rules

`firestore.rules` restricts every note to its owner. Push it with:

```bash
npx firebase-tools deploy --only firestore:rules --project sync-notes-c252b
```

Until these are deployed, the database is running on whatever rules the console
created it with — check them before putting anything real in there.

## Testing on a device

A physical phone over USB is the primary target. From `Android App`:

```bash
./gradlew :app:installDebug
```

Google sign-in is one reason to prefer a real phone: it needs Play services and a
Google account already on the device, which a bare emulator doesn't have.

Note that the in-app updater only ever delivers *released* APKs, so it is not a
substitute for this during development — use it to ship, use `installDebug` to
iterate.

An AVD named `syncnotes_test` (Pixel 6, API 34, google_apis) also exists if a second
device is ever useful. The `google_apis` image matters — Firebase needs Play
services, which the plain `default` images lack.

```bash
"$ANDROID_HOME/emulator/emulator" -avd syncnotes_test
```

## Updates

On launch, each installed client asks
`api.github.com/repos/justAleks0/sync-notes/releases/latest` whether its own
compiled-in version is the newest, and picks the asset matching its platform
(`.exe` for Windows, `.apk` for Android). Up to date means nothing is shown at all.
Behind means a banner with a **Download now** button that downloads and installs.

The website has no update check on purpose — a web app is already the newest version
the moment it loads.

Releasing is one command, and it is what keeps the tags and the compiled-in versions
in agreement:

```bash
./scripts/release.ps1 -Version 0.2.0
```

It stamps the version into both `package.json` files and `build.gradle.kts`, builds
the website, the Windows installer and the release APK, then tags, pushes, and
creates the GitHub release with both artifacts attached. Add `-NoPublish` to build
without releasing.

Deliberately local rather than GitHub Actions: the APK is signed with this machine's
debug keystore, and CI generates a fresh one on every run, so a CI-built APK could
never install over an existing one. Move this to CI once there is a real release
keystore stored in GitHub secrets.

Android version codes are derived from the version name (`0.1.0` -> `100`,
`1.2.3` -> `10203`), so they always increase without anyone tracking a separate number.

## Accounts

An account can hold both sign-in methods at once. Settings → **Sign-in methods**
connects Google to a password account, or adds a password to a Google account —
same account, same notes either way. Firebase blocks removing the last remaining
method, so the *Disconnect* control is hidden until a second one exists.

Sensitive changes (removing a method, changing a password) fail with
`auth/requires-recent-login` if the session is more than a few minutes old. Both
clients catch that, ask you to confirm, then replay the change automatically.

## Known gaps

- **Google sign-in on Android is not wired up.** Android verifies the app by its
  signing certificate rather than an OAuth redirect, and no SHA-1 is registered for
  `com.justaleks.syncnotes` — so `google-services.json` has only a web client
  (`client_type: 3`) and no Android one. To enable it:
  1. Firebase Console → Project settings → Your apps → the Android app → **Add fingerprint**
  2. Paste the debug SHA-1 (`keytool -list -v -keystore ~/.android/debug.keystore
     -alias androiddebugkey -storepass android`), and the release one when you have
     a release keystore
  3. Re-download `google-services.json` into `Android App/app/`
  4. Then the Credential Manager sign-in flow can be added

  Until then, the workaround is built in: add a password in Settings on the website,
  and sign in with that on the phone.
- **Conflict handling is last-write-wins.** Editing the same note on two devices at
  once will keep whichever save lands last, per field. Fine for one person on
  several devices; not enough for real collaboration.
- No note deletion undo, no folders/tags, no rich text, no attachments.
