# Thumbrest

Thumbrest applies hands-free hold and turbo to a physical controller on rooted
Android. It lists compatible connected pads, edits one controller at a time, and
publishes **Thumbrest Virtual Gamepad** for emulators and games.

## Try it

1. Use a 64-bit ARM Android 10+ device with Magisk root and SELinux enforcing.
   Install the APK, open Thumbrest, tap **Connect controllers**, and grant
   superuser access. Allow notifications if you want **Stop** in the notification.
2. Choose a controller while its controls are idle. The diagram lights up as you
   press buttons and move the sticks. While **Editing**, press a physical button
   or tap it on the diagram to cycle **Neutral → Held → Turbo → Neutral**.
   **Reset** clears all modes. The virtual pad stays neutral until you apply.
3. For a racing game, press the accelerator button once in the editor so it shows
   as **Held**. Tap **Apply modes** and open the emulator. Select **Thumbrest
   Virtual Gamepad** in its controller settings and bind it to the player slot.
   The accelerator should stay down while steering still responds normally.
4. Return to Thumbrest and tap **Edit modes**. Press that same button again to
   set **Turbo**, choose a rate, and apply. The game should see repeated down
   and up transitions after your thumb leaves the button. Slow the rate if the
   game misses presses.
5. Use **Stop** from Thumbrest or its notification. The virtual controller
   should disappear and the physical pad should respond directly again. Verify
   this once each with **Stop**, force-stop, and a physical disconnect, using a
   disposable game session or your emulator’s controller tester. If the
   emulator has retained its virtual-pad binding, select the physical controller
   again in its settings.

The preview handles standard digital gamepad buttons and common joystick axes
from one Linux gamepad event node. Analog-only controls pass through as axes.
The physical pad may still appear by name while grabbed, so select the virtual
pad explicitly. Button modes last for the session. This APK uses a session-bound
Magisk root service and Android's uinput device. It requires a kernel exposing
uinput and access to the selected physical input node.

## Build with Devbox

Install Devbox and use macOS or x86-64 Linux with an available C++ compiler for
the host checks. The first SDK setup downloads approximately 1.2 GB from Google
and stores the verified archives and extracted toolchain under `.tool-cache`.
The Android SDK components are subject to Google's Android SDK license.

```sh
devbox run sdk
devbox run check
devbox run build
```

The signed debug APK is produced at
`app/build/outputs/apk/debug/app-debug.apk`. To install it on an attached test
device, run:

```sh
.tool-cache/android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The SDK bootstrap
pins platform 36, build tools 36.0.0, NDK 29.0.14206865, CMake 3.22.1, and
platform-tools 37.0.3. Gradle pins Kotlin, the Compose BOM, and the libsu root
service library. The test command runs the native button-mode checks and
Android lint.

## GitHub builds and release signing

Pushes to `main` and manual workflow runs check the native button modes, run
Android lint, and build a release APK using the repository's signing secrets.
The `Thumbrest-android-arm64` Actions artifact contains `Thumbrest.apk` and its
SHA-256 checksum. The build verifies the APK signature, compares the signer
with the supplied keystore certificate, and checks archive integrity and 16 KiB
alignment before uploading it. CI uses the Actions run number as the Android
version code.

Pull requests run the checks and produce a debug APK. Their workflow receives
no release signing secrets. Both paths use the project's pinned Devbox JDK and
project-local Android toolchain.

The repository uses these Actions secrets:

| Secret | Contents |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 of the persistent signing keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing alias, `thumbrest` for the initial key |
| `ANDROID_KEY_PASSWORD` | Signing key password |

For a local release, provide those four values in the environment and run
`devbox run release`. The script creates a private temporary keystore and
removes it on exit. Output appears in `dist/Thumbrest.apk` and
`dist/Thumbrest.apk.sha256`. Keep the persistent keystore and passwords backed
up outside the tool cache.

The project targets ARM64 Android 10+ with Magisk. The initial local checks
compile Kotlin and the native engine, run button-mode regressions, and run
Android lint. Magisk operation, physical controllers, and emulator gameplay
still require the on-device walkthrough above.
