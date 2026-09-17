# OpenLock for Android

The phone side of the OpenLock smart lock. Jetpack Compose, [miuix][miuix] for
the UI, and BLE to the lock. No cloud, no account: the phone is paired directly
with each lock.

[miuix]: https://github.com/compose-miuix-ui/miuix

## What it does

| Feature | How |
| --- | --- |
| Add and keep multiple locks | Scan for locks advertising the OpenLock service; each is stored with its last known state |
| Open / close / stop the bolt | `UNLOCK`, `LOCK`, `STOP` over an encrypted GATT link |
| See the bolt state and battery | `STATUS` plus `EVT` pushes after every move, drawn as a side-on view of the bolt that animates between positions |
| Re-learn the travel | `CALIBRATE` drives the bolt into both end stops and records the span |
| Let another phone join | Pairing mode: `PAIR` opens the lock's pairing window; the screen shows the lock's real countdown |
| See who can open the door | `BONDS` lists the phones bonded on the lock, `UNPAIR` revokes one |
| App lock | Fingerprint / face / device credential in front of the whole app |
| Appearance settings | Light / dark / follow-system, five accent colours or wallpaper-derived (Monet), plus blur, floating bar, liquid glass, predictive back and interface scale |

## Layout

```
app/src/main/java/moe/openlock/app/
  ble/         BleUuids, Protocol (line codec), LockScanner, LockConnection
  data/        LockDevice, DeviceStore, SettingsStore, LockRepository
  security/    BiometricGate
  ui/          AppRoot and one file per screen, theme/Theme.kt
  ui/liquidglass/  the liquid-glass pill bar, ported from the miuix sample app
  vm/          LockViewModel
```

`LockConnection` owns one GATT session and speaks strictly one command at a
time; `LockRepository` owns the device list, the single live connection and the
command policy (timestamps, retries, the bond list).

`ui/liquidglass/` is copied from miuix's own example app rather than invented
here, because the library does not publish that composable. Each file's header
records the upstream revision it came from, and the theme switch it used is
rewired to this app's palette; re-apply upstream fixes by diffing those files.

## Appearance

Everything lives in **Settings → 主题**. Colours are persisted
(`SettingsStore.theme`, exposed as a flow so the whole activity recomposes) and
applied by `ui/theme/Theme.kt`; the chrome switches are `SettingsStore.ui`.

**Colours.** Both choices go through miuix's `ThemeController` in one of its
`Monet*` modes, which is what makes a single code path cover two different
things:

- **跟随壁纸** leaves `keyColor` null, so miuix asks the platform for the real
  wallpaper-derived palette (Android 12+);
- **a named accent** passes its seed as `keyColor` and miuix generates the full
  tonal palette from it.

The `Monet*` modes are the only ones that honour `keyColor`; `Light`/`Dark`
ignore it. Note that miuix's `colorsFromSeed()` and `monetSystemColors()` are
`internal` even though they are declared in the public package - build a
`ThemeController` rather than trying to call them.

**Chrome.** These are separate from the palette because they are independent
decisions - a blue accent says nothing about whether the bar floats:

- **界面模糊** frosts the content behind the bar. Off falls back to a plain
  translucent surface - a working bar, not a broken effect, because the runtime
  shader it needs is not available everywhere.
- **悬浮底栏** floats the bar over the content instead of docking it; the
  content runs underneath so there is something to blur.
- **液态玻璃** pushes the frost further (more blur, a refraction lens, an edge
  highlight). It needs the two above it and is disabled without them.
- **预测性返回手势** lets page changes follow the system back gesture; off
  swaps in an immediate transition it cannot be cancelled out of.
- **界面缩放** scales dp and sp together, so the whole interface grows rather
  than just the text.

## Icon

The launcher icon is the organisation's mark (`res/drawable/ic_openlock_mark.xml`)
- an open shackle over a ring split into four segments - drawn as a vector, so it
stays crisp at every size and can be tinted. It is black line work on white,
matching the logo it is taken from, and a monochrome layer is supplied for
Android 13+ themed icons. The same drawable is shown in Settings → 关于.

## Building

Requires **JDK 21** and an Android SDK with **platform 37**, because miuix and
Compose 1.12 declare `minCompileSdk 37`. JDK 21 rather than 17 is forced by
miuix-nav: it ships JVM-21 bytecode and its `entry<T> {}` builder is inline, so
a 17 target fails with *Cannot inline bytecode built with JVM target 21*.

```bash
# point at your SDK (this file is git-ignored)
echo 'sdk.dir=/path/to/Android/Sdk' > local.properties

./gradlew :app:assembleDebug
```

Toolchain, chosen to match what miuix requires: Gradle 9.7.0, AGP 9.3.1, Kotlin
2.4.10, compileSdk 37, minSdk 33. miuix is pinned to `0.9.4-rc01`; `0.9.3` is a
drop-in fallback (same API for what this app uses) if the RC misbehaves.

## Builds

Two workflows, both signing with the same key so any build can be installed over
any other:

| Workflow | Trigger | Produces |
| --- | --- | --- |
| `debug.yml` (Beta Builds) | any branch push, or manual | a debug APK, versioned `<last tag>-beta.<run number>` |
| `release.yml` (Release Builds) | a `v*` or `*.*.*` tag, or manual | a release APK and AAB, published to the GitHub release |

The version is `0.0.1-beta.<run number>` until the first tag exists.

**Signing.** `keystore/openlock-release.jks` is committed on purpose: a GitHub
runner otherwise generates a throwaway debug key per job, and no two builds
would be able to upgrade-install over each other. The passwords are not in the
repository - they come from GitHub Secrets (`OPENLOCK_RELEASE_*`), read locally
from `keystore.properties` instead. Without either, the build still succeeds and
just falls back to the debug signature, which keeps a fresh clone buildable. Both
workflows verify the resulting signature rather than trusting that it happened.

Note the trade-off of committing the keystore to a public repository: the key
material is public. Anyone who also obtains the password could sign an APK that
Android accepts as an upgrade. That is acceptable for test builds; a real release
should use a keystore that is not in the repository.

## The protocol

The lock is a GATT peripheral and this app is always the central. The full wire
contract lives in the firmware repository as `BLE-PROTOCOL.md`; the summary that
matters here:

- Service `4f70656e-4c6f-636b-0000-000000000001`, with a write-only CMD
  characteristic (`...0002`) and a notify EVENT characteristic (`...0003`).
  **Both require an encrypted, bonded link** - nothing works before pairing.
- Lines are `\n`-terminated ASCII. One command, one `OK`/`ERR` reply, in
  lockstep (there are no request ids).
- Notifications are chunked to the negotiated MTU and must be buffered until a
  `\n` arrives; the status line is long enough that this happens even at 256.
- Every privileged command carries the phone's Unix time. The lock refuses
  anything older than 120 s, and refuses to move its own clock backwards. The
  app sends `max(phone_now, lock_clock)` and retries once with the lock's clock
  if it answers `ERR stale` or `ERR time-behind`.
- The commands in use: `TIME`, `STATUS` (also as `EVT` pushes), `PAIR`,
  `UNLOCK`, `LOCK`, `STOP`, `CALIBRATE`, `BONDS`, `UNPAIR`. `OK` on a movement
  command means *queued*, so the app waits for the `EVT` rather than claiming
  the door moved.
- `UNPAIR` takes an **index into the bond list**, not an identity, so the list
  is re-read and range-checked immediately before the command. Unpairing this
  phone drops its own link before any reply could arrive, so the app sends that
  one without waiting and treats the disconnect as the confirmation.

The app never removes the system pairing. If it did, the lock would still
remember this phone and would ignore the re-pairing attempt until an owner
opened its pairing window.

## Pairing rules the app has to live with

The lock uses **Just Works** pairing, so there is no code to type and no
man-in-the-middle protection. It compensates with a window policy:

| Lock state | What happens |
| --- | --- |
| No phone bonded yet | Open: the first phone to connect becomes the owner |
| Has an owner | An unbonded phone is disconnected right after connecting (HCI `0x13`) |
| Window opened via `PAIR` or the console | Open for that many seconds |

So "the lock hung up on me" is a normal answer, not a bug, and the app says so
rather than retrying. Adding a phone is a deliberate act performed by an
existing owner - that is the whole access-control story, so the pairing screen
states it plainly.

## Battery

`battery` is a real field on the wire, but the current lock hardware has no
voltage divider, so the firmware reports a fixed **100** and the UI says as much
under the value. The field is optional: a firmware that does not send it is
handled (shown as unknown), so nothing has to change here when the hardware
gains a divider.

## Security notes

What is in place:

- **Bluetooth permissions** are requested at runtime and the scan is filtered to
  the OpenLock service. `neverForLocation` is set because scan results are never
  used to infer location.
- **App lock**: `BIOMETRIC_STRONG | DEVICE_CREDENTIAL`, so a phone without a
  usable sensor still works with its device credential. Off by default,
  confirmed once when enabled, and the prompt fires as soon as the gate screen
  appears rather than waiting to be asked.
- **The app never removes the system pairing on its own initiative**; forgetting
  a lock only drops it from this app's own list.

What is not:

1. **There is no second factor.** The bond is the whole credential - see the
   pairing section above. Anyone who can unlock a bonded phone can open the
   door, unless the app lock stops them first.
2. **The app lock does not protect the lock.** An attacker who gets past it
   still needs this phone to be one of the lock's bonded peers. The real access
   control is the lock's own pairing policy.
3. **Nothing is logged or audited.** The app keeps no history of opens, and the
   lock does not either.
4. **The lock cannot measure its own battery yet**, so a full battery reading is
   not evidence of anything.

## Status

The app has run **on a real phone against real hardware** (Xiaomi 17, Android
17). Verified on device:

- scan finds the lock and shows its RSSI;
- **add** works end to end - connect, the system pairing prompt, a bonded and
  encrypted link, CCCD subscribe, then live `STATUS`;
- the device screen shows the bolt state and battery from the lock;
- forget → scan → add again works (the re-add exercises the cached-attribute-
  table path, and it came up clean);
- Settings: light / dark / follow-system and the accent choices apply
  immediately and persist.

Built and compiling, but **not yet exercised on a phone**: the paired-phone list
and revocation, the calibration action, the pairing-window readout, the device
state drawing, and the liquid-glass bar. Treat those as unverified UI.

On the lock side, `BONDS`, `UNPAIR` and `CALIBRATE` were verified over the
serial console against the real board, including the error paths and the
recovery where removing the last phone leaves the lock unowned and reopens
pairing.

Still not done: driving the bolt over BLE from the app, which is blocked on the
mechanism rather than on either piece of software - the encoder reads zero
counts, so the lock cannot calibrate travel (see the firmware repository). The
two-phone test is also outstanding: a second phone should be refused until the
first opens pairing mode.

Two things to watch when adding a lock:

1. **A stale GATT cache.** Android caches the attribute table per device and only
   invalidates it on a Service Changed indication. If the phone cached a table
   before the lock's firmware was flashed, discovery comes back without the
   characteristics and the failure looks like "this is not an OpenLock".
   `LockConnection` now clears the cache (`BluetoothGatt.refresh()`, best-effort
   hidden API) and rediscovers once before reporting anything. The firmware
   repo's `BLE-PROTOCOL.md` §10 has the detail.
2. **The bonding prompt.** `LockConnection` bonds explicitly (`createBond()`,
   with an `ACTION_BOND_STATE_CHANGED` receiver) before subscribing, because the
   CCCD write needs an encrypted link. The system shows its own "pair with
   OpenLock-XXXXXX?" prompt - the app cannot and does not draw one. If the user
   dismisses it, `connect()` fails with "pairing was rejected".

## Licence

See `LICENSE`.
