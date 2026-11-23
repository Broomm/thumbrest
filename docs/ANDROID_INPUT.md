# Android input design and evidence

## Product behavior

Thumbrest 0.2 applies button modes live. Hardware presses always reach the game
according to the selected mode; only screen taps cycle Default, Held, Turbo,
Default. There is no Apply phase. The virtual pad lives for the entire captured
session, including when every button is Default. Root connection and selection
are automatic when the remembered controller is unambiguous. Stop persists a
pause until Resume; disconnect/reconnect starts a fresh Default session.

Controller preference uses bus/vendor/product/version plus optional physical
and unique identifiers. Names and event numbers do not establish identity.
Starting a session additionally verifies the scanned event node's device,
inode, and character-device number. Ambiguous remembered hardware requires
an explicit selection.

## Why a virtual pad remains necessary

The generic backend uses evdev plus uinput. EVIOCGRAB directs source input to
Thumbrest's reader. Writing events back to that same evdev device would inject
additional events into the same stateful input stream, including the grabbed
client, rather than replace the hardware's state. Reliable direct modification
would require a driver, producer-specific integration, or an Android framework
hook. This implementation does not install any such hook.

Primary references:

- [Linux uinput interface](https://docs.kernel.org/input/uinput.html).
- [Linux input event state](https://docs.kernel.org/input/event-codes.html).
- [Android kernel input injection and exclusive grabs](https://android.googlesource.com/kernel/common/+/f64d204b5cf21b282445ba2fa357d970028c9f52/drivers/input/input.c).
- [Android EventHub device watching](https://android.googlesource.com/platform/frameworks/native/+/ed593ec7a9a4c1f5635242996fefc4b8ca9a5ae6/services/inputflinger/reader/EventHub.cpp).

## Hiding and recovery

Grabbing alone blocks events but leaves the original in Android's controller
list. SourceVisibility keeps a hard link to the opened device inode inside a
root-only, randomly created directory in `/dev`, then unlinks its public event
node. Android EventHub observes IN_DELETE, closes its reader, and removes that
entry. Thumbrest continues reading its already-open descriptor and emits input
through a replacement uinput device. The original kernel device still exists;
this is removal from Android's usable device enumeration, not kernel removal.

Before hiding, a separate guardian process acknowledges readiness. It keeps
only its source, directory, backup and recovery-channel descriptors. Its child
path uses prepared memory and raw system calls, with no JNI, allocations,
locks, Android logging, or C++ cleanup after fork. Enumerating descriptors in
the child also covers descriptors opened concurrently by ART/Binder before the
fork. It retains no uinput descriptor, so a dead writer cannot keep synthetic
held output alive through the guardian.

On writer death or a normal shutdown request, the guardian releases the grab,
checks that its exact opened source still exists, and restores the hard link
exclusively. That preserves inode, ownership, mode, and SELinux label. It does
not overwrite a newly occupied event path. Disconnects do not recreate stale
device nodes. The writer also monitors guardian death and attempts restoration
itself. Startup failures unwind the prepared backup. Restoration errors are
reported through the root service; an unrecoverable node conflict retains its
private backup instead of replacing another device. Reboot recreates `/dev`
from the device's normal producers if recovery cannot complete.

No global input configuration, OEM settings, SELinux mode, or Magisk policy is
changed. No third-party firmware code is copied or shipped.

## Thor firmware interaction

Inspected device: Android 13/API 33, ARM64, Magisk 31.1, firmware
`Thor_V1.0.0.377_20260206_165408_user`; SELinux already permissive. The built-in
controller is a uinput device named Odin Controller, bus USB, vendor 0x2020,
product 0x0111, version 0. Its right stick uses centered Z/RZ and triggers use
GAS/BRAKE. Its vendor key-layout file also defines Home/Back/volume keys.

The retained crash buffer showed AYN's `com.odin.mapping` aborting at local
14:29:24 and 14:31:51 on 2026-11-23, with JNI global-reference-table exhaustion
and approximately 50,829 Toast callback objects. Android recorded Thumbrest's
own earlier exit as a user force-stop, not an app crash. The AYN crash recurred
at 16:48:16 during integration tests with unfamiliar generic fixture pads.

Read-only inspection of `/system/app/TouchMapping/TouchMapping.apk` and its
`librsinput.so` found an automatic gamepad-capture path and an `rsinput_assert`
exclusion comparing vendor/product against its own outputs. A transient pad
named Thumbrest Virtual Gamepad but carrying AYN's IDs was observed while
AYN's mapping process was the only owner of `/dev/uinput`. This supports the
inference that the OEM capture/recreation path interfered with the early
fixture enumeration and open failures. Reusing the source vendor/product
identity in Thumbrest's output, and the AYN pair in fixtures, avoided that
feedback in subsequent checks; the OEM process remained alive through the
corrected fixture suite and real built-in-controller probe.

The production output preserves the source bus/vendor/product/version, so
Android uses the same vendor key layout. Additional source keys pass through
as well as ordinary gamepad buttons and axes. Thumbrest's displayed controller
name stays distinct for emulator binding and for ignoring its own input in the
companion UI.

Inspected-file SHA-256 values (evidence only, not bundled components):

- TouchMapping.apk: `215731e1dace972caa0685581f82e146ab63615bdd086fe4124677a495d15b08`.
- librsinput.so: `93c7e24c47f2682c74b559fcd4a3a0e437f5149bf244f0da78cee0ec2a9e64e0`.

## Verification boundary

Host mode regressions, pure Kotlin selection checks, Android lint, native
compilation, and real Android kernel/EventHub/InputReader checks are separate
checks. Device regressions cover live modes, ordinary passthrough, correct
stick previews, identity, hiding, normal stop, disconnect, forced writer death,
and a conflicting replacement node. The dedicated physical probe verifies the
actual built-in pad in Default mode without generating gameplay presses.

The app's secondary-display window uses FLAG_NOT_FOCUSABLE while ready to keep
controller key focus with the game while accepting touch. Actual Thor/Burnout
focus, screen interaction, and gameplay must be owner-verified. No result on
this permissive Thor establishes compatibility with enforcing kernels, other
firmware, or other devices. Emulator-specific binding may still be needed once.
