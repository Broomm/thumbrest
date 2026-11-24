# Thumbrest

Thumbrest applies hands-free hold and turbo to a physical controller on rooted
Android. It lists compatible connected pads, edits one controller at a time, and
publishes **Thumbrest Virtual Gamepad** for emulators and games.

## Try it

1. Use a 64-bit ARM Android 10+ device with Magisk root. Install the APK and
   open Thumbrest. Grant superuser access once; allow notifications to keep
   **Stop** accessible outside the app. Thumbrest connects automatically.
2. With one compatible controller, the pad becomes **Ready** automatically.
   With several pads, choose one. Thumbrest remembers its hardware identity and
   reconnects to it when available. Identical pads without a unique identifier
   require a choice. Every new session starts with all buttons **Default**.
3. The virtual pad is already live: **Default** passes the physical button
   through. Tap a button on the screen to cycle **Default → Held → Turbo →
   Default** immediately. Physical presses always belong to the game. There
   is no editing phase or Apply button, and changing modes keeps the same
   virtual controller connected.
4. On the Thor, put Thumbrest on the bottom screen and your emulator on top.
   At the start of a race, tap **A** once to hold acceleration. Tap **A** twice
   after the race to restore ordinary input. **All default** clears every
   synthetic hold/turbo while preserving actual physical input. Turbo runs at
   the selected 1–60 presses/sec; the rate is remembered.
5. Android sees **Thumbrest Virtual Gamepad** in place of the captured original.
   If your emulator requires explicit controller binding, select that pad once.
   Upgrading from the first prototype may require rebinding because the virtual
   pad now preserves the source vendor/product identity and key layout.
6. **Stop** in the app or notification removes the virtual pad and restores the
   physical controller. Automatic connection stays paused until **Resume**.
   Disconnecting a pad ends its input session; reconnecting the remembered pad
   starts a fresh Default session automatically.

Under **Button style**, choose **Xbox**, **Switch**, or **PlayStation**.
The choice is remembered and takes effect immediately, including during a live
session. Xbox uses its colored ABXY labels and LB/RB/LT/RT; Switch uses B at the
bottom, A on the right, L/R/ZL/ZR and minus/plus; PlayStation uses colored
Cross/Circle/Square/Triangle glyphs and Share/Options. The styles change only
presentation. Physical button positions, input mappings, and active Held/Turbo
modes stay the same. Xbox is the default.

Enable **Invert face glyphs** in the same section to swap **A/B** and **X/Y**
for controllers with the opposite face-label arrangement. PlayStation swaps
Cross/Circle and Square/Triangle, including glyph colors. This saved option is
independent of the style and applies immediately. It changes the labels shown
on each control, including spoken names; the physical input, tap targets, and
active Held/Turbo modes stay attached to their original buttons. It is off by
default, preserving your existing layout until you enable it.

The preview handles digital gamepad buttons and common joystick axes from one
Linux event node. Additional physical keys pass through; analog-only controls
remain axes. Rumble forwarding is not implemented. Button modes last for the
session. Root and a kernel exposing uinput are required. No Magisk module or
reboot is needed to start a supported session.

The native engine holds the physical input node open, keeps its original inode
in a private temporary directory under `/dev/.thumbrest-*`, and hides its public
node so Android removes the duplicate entry. An independent recovery process
restores the node and releases the grab on Stop or writer death. This preserves
its permissions and SELinux label. No controller settings, firmware, or global
SELinux state are changed. See [Android input design and evidence](docs/ANDROID_INPUT.md).

For owner verification, use an emulator controller tester or a disposable race:
confirm ordinary menus, one-tap Held, two-tap return to Default, steering beside
Held/Turbo, and continued game focus when touching the lower screen. Verify
Stop and force-stop return direct physical input. The secondary-display window
accepts touch without requesting controller key focus. Its virtual pad is
associated with the upper/default display so a lower-screen touch cannot
redirect its events. The owner confirmed the Thor workflow worked on
2026-11-24; the new button styles still need a visual check on the device.

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
service library. The test command runs native button-mode checks, pure Kotlin automatic-selection
regressions, and Android lint.

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

The project targets ARM64 Android 10+ with Magisk. The build compiles Kotlin
and the native engine; CI also compiles the Android integration harness.

## Native device checks

Close games before running the generated-input suite: its disposable pads emit
ordinary button and axis events into Android. With the test device attached
over USB debugging and Thumbrest stopped, run:

```sh
ANDROID_SERIAL=your-device-serial devbox run device-check
```

Set `ADB` to an existing platform-tools `adb` executable to use it instead of
the project-local copy. The command cross-compiles the production input engine,
runs it as root against disposable kernel gamepads, then removes its temporary
executable and directory from the device. It leaves physical controller input
available for the app. CI also compiles this harness through
`devbox run -- bash tool/check-device.sh --build-only`.

The native checks passed on 2026-11-23 on an AYN Thor running Android 13 (API
33), ARM64, Magisk 31.1, firmware `Thor_V1.0.0.377_20260206_165408_user`.
The device's existing SELinux mode was permissive. Coverage includes:

- Default physical input, immediate screen-command hold/turbo/default changes,
  simultaneous ordinary button/axis passthrough, and preserved vendor mappings.
- Right-stick previews for RX/RY and centered Z/RZ, with unipolar Z/RZ kept as
  triggers.
- Actual Android EventHub/InputReader removal of the original and registration
  of its replacement, followed by restoration on Stop.
- Stale identity rejection, restoration of the same inode/ownership/permissions,
  disconnect cleanup, killed-writer recovery with a button held, and refusal to
  overwrite a replacement node followed by recovery when that conflict ends.

The fixtures use AYN's own vendor/product pair because this firmware captures
unfamiliar gamepads and can enter a device/toast feedback loop. That behavior
was investigated separately; the production virtual pad copies its source
identity instead of publishing a generic zero vendor/product pair.

An explicit, separate probe also passed against the real built-in **Odin
Controller**: live Default input, Android enumeration/hiding, a stable session,
and exact physical-node restoration. It generates no synthetic gameplay
presses. To repeat that check on a Thor, with the app stopped:

```sh
ANDROID_SERIAL=your-device-serial devbox run -- bash tool/check-device.sh --physical-probe
```

The probe requires exactly one freshly identified built-in Thor pad; it does
not choose an arbitrary controller. The normal `device-check` command only
uses disposable generated pads.

The signed APK **0.2.0 (7)** from revision `a637466` also passed packaged-app
checks on that Thor. Existing Magisk permission was used, the remembered pad
became Ready automatically, and Android listed its replacement with the upper
display's unique ID. The lower-screen window was non-focusable while the upper
emulator window retained key focus. Stop removed the display association and
restored the original node. The pause survived force-stop and reopening;
Resume reconnected automatically. Terminating the identified root writer and
force-stopping the app both restored the exact physical inode, ownership, mode,
and device number, removed virtual output, and left no recovery backups.
Reopening reclaimed the Thumbrest-only display route. AYN's mapping process
remained running throughout these checks.

These packaged checks used ADB lifecycle commands and Android registry readback;
they did not generate gameplay button presses or automate screen touches.
The owner subsequently reported this version worked perfectly on 2026-11-24.
The visual-style update passed compilation/lint and signed CI. Build **8** was
subsequently installed on the same Thor on 2026-11-24: it automatically returned
to Ready with all buttons Default, the original hidden, upper-display routing
confirmed, and the lower-screen window non-focusable. App startup logs contained
no current errors, and AYN's mapping PID stayed unchanged. The new style
selector and its appearance still need the owner walkthrough; these checks did
not automate screen touches. Enforcing-mode behavior and other Android devices
remain unverified.

The glyph-inversion update, signed **build 9** from `caee5c3`, also passed CI
and was installed on this Thor on 2026-11-24. Startup, controller hiding, upper
display routing, and the touch-only lower-screen window passed ADB readback.
The owner's PlayStation style stayed selected and inversion was off after the
upgrade. The new toggle's appearance and operation through screen taps remain
part of the owner walkthrough; no touches or gameplay inputs were automated.
