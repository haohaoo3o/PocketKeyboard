# PocketKeyboard (口袋键鼠)

Turn your Android phone into a **Bluetooth keyboard + trackpad** peripheral: monochrome theme, large minimal surfaces, seamless keycaps, Apple-style design language, and a pure-black OLED-friendly background.

Pair it with an iPad / iPhone / Mac / Windows device and use your phone as their Bluetooth keyboard and trackpad. Multiple hosts can be paired at once, with on-demand switching of the active control target.

**中文版本：[README.md](README.md)**

## Screenshots

| Pairing | Keyboard (portrait fused layout) |
| --- | --- |
| ![Pairing](docs/screenshots/01_pairing.png) | ![Keyboard](docs/screenshots/02_keyboard.png) |

| Fused page + system IME | Numeric Keypad |
| --- | --- |
| ![System IME](docs/screenshots/03_trackpad.png) | ![Keypad](docs/screenshots/04_numpad.png) |

## Features

- **Bluetooth HID peripheral**: registers a composite keyboard + trackpad device via the system `BluetoothHidDevice` profile (standard HID report descriptors), so hosts recognize the phone as a Bluetooth keyboard/trackpad
- **Broadcast name distinct from the phone**: on launch the app renames the local Bluetooth device to `PocketKeyboard-<brand>` (e.g. `PocketKeyboard-redmi`) so hosts can tell this is PocketKeyboard, not the phone itself
- **Pairing screen**: shows the fixed pairing code `0000` and, while pairing, the **actual live SSP number** (identical to what the host displays — see "Pairing code" below); supports pairing with multiple hosts simultaneously; on first connection to a host you choose "Apple device / Other device" to adapt layout and gestures (choice is persisted)
- **Add controlled devices**: scan nearby Bluetooth devices and start pairing right from the app (`createBond`); adding / connecting / disconnecting / deleting hosts is fully app-driven (swipe-left to delete); the in-app "Connect / Disconnect" button manages connections (disconnect is sticky — host auto-reconnect won't override it)
- **Connection reliability**: every connect attempt has a 9-second watchdog (lost callbacks / unresponsive hosts are failed and retried with backoff — "Connecting…" never hangs forever) plus a 3-second system-truth reconciliation; wedged HID registration (some ROMs wedge it after a force-kill) self-heals automatically (rooted devices restart the Bluetooth stack)
- **CONTROL button**: grey and disabled until a host is connected, then turns black and enters the control UI
- **Five-finger gestures** (global, with animated feedback and text hints):
  - Pinch in → switch to trackpad mode
  - Pinch out → switch to keyboard mode
  - Swipe left/right with five fingers → cycle the active control target among paired hosts
- **Portrait fused layout** (shared by keyboard and trackpad modes): trackpad on top (with the minimal "123" keypad toggle at the top-right, left/right click zones split by a short vertical line, and the active host name) plus a 26-key phone-style QWERTY on the bottom (three letter rows 10/9/7 + shift / backspace / space / return; tap shift for one-shot uppercase, hold it for combo capitals); keycaps always render letters uppercase
- **87-key keyboard** (landscape fullscreen): TKL layout with Apple and Windows keymaps; black background, white text, seamless keys; press-down visual feedback plus haptics
- **Landscape fullscreen**: in landscape the keyboard mode fills the screen with the 87-key TKL and the trackpad mode with the trackpad (configChanges in the manifest means no Activity recreation on rotation)
- **Auto-hiding dock**: the bottom mode switcher hides by default on the keyboard/trackpad pages to free up space; a single-finger swipe inward from the left or right screen edge (more than 24dp) brings it back with an Apple-style spring, and it hides again as soon as the page mode changes
- **fn combos** (sticky fn, with an on-screen hint):
  - `fn + Space`: cycle screen backlight brightness (iOS-style brightness HUD)
  - `fn + C`: cycle keycap text color (white / orange / red)
  - `fn + S`: cycle keycap font size (small / medium / large)
  - `fn + V`: cycle haptic strength (off / weak / medium / strong), synced with the trackpad feedback
- **Media keys on the F-row**: default to volume, mute, brightness, previous/next track, play/pause; hold `fn` for the literal F1–F12
- **Trackpad screen**: two gesture sets —
  - Windows mode: bottom left/right button zones (with drag), two-finger scroll, pinch-to-zoom (mapped to Ctrl+scroll), three-finger up/down/left/right, four-finger tap = Win key
  - Apple mode: one-finger tap = left click, two-finger tap = right click, two-finger scroll, pinch/rotate (mapped to Ctrl+scroll), three-finger up/left/right
- **Numeric keypad**: minimal "123" toggle at the top-right of the trackpad, opening a bottom sheet with a 4×6 keypad (including 0/00, backspace, return, operators, clear) for heavy numeric input
- **OLED-friendly**: pure black background throughout, white text and icons

## Requirements & Build

- JDK 17, Android SDK (Platform 35, Build-Tools 35.x)
- minSdk 28 (Android 9) · targetSdk 35 · Kotlin 2.x · Jetpack Compose · Gradle 8.11.1 (wrapper included)

```bash
git clone https://github.com/haohaoo3o/PocketKeyboard.git
cd PocketKeyboard
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or simply open the project directory in Android Studio.

### Tests

```bash
./gradlew testDebugUnitTest   # 394 unit tests (incl. Robolectric Compose UI smoke tests)
./gradlew lintDebug           # static analysis
```

## Usage

1. Install and open the app, grant the Bluetooth permissions when asked
2. Add a controlled device (either way):
   - tap "Add controlled device" in the app and pick the host from the scan list — the app starts pairing directly; or
   - tap "Discoverable" (top-right), then **select this phone in the Bluetooth menu of the host device** (iPad / Mac / Windows, etc.)
3. Complete pairing (see "Pairing code" below); the device row shows "Connected" once done
4. On first connection the app asks whether the host is an Apple device or another device, and adapts the keyboard layout and trackpad gestures accordingly
5. Tap CONTROL to enter the control UI; five-finger gestures switch modes / hosts at any time

### Pairing code

The digits shown on the pairing screen are the **exact digits used for pairing**:

- **Fixed pairing code `0000`** (idle): enter it when the host asks for a PIN / pairing code;
- **While pairing**: the screen shows the **actual live SSP number** — identical to the one on the host's screen;
- when a confirmation click is required (numeric comparison / just works), tap "Pair" in the system confirmation dialog.

> Platform limitation: Android hides `setPairingConfirmation` / `setPin` behind `BLUETOOTH_PRIVILEGED` (signature|privileged), so a normal app cannot complete the final confirmation programmatically — the system dialog carries that last click. The **number itself is always sourced from the app** and matches the host exactly.

## Project Structure

```
app/src/main/java/com/pocketkeyboard/app/
├── MainActivity.kt          # Single Activity: permissions, five-finger gesture layer, page container
├── hid/                     # Bluetooth HID backend: HidDeviceTransport (system profile) + NullHidTransport fallback
├── gesture/                 # Five-finger gesture engine, gesture arbiter (60ms window), HUD, page transitions
├── keyboard/                # 87-key / 26-key layout models, seamless keycaps, fn combos, portrait fused screen, HID report engine
├── trackpad/                # Trackpad gestures, Win/Apple gesture sets, numeric keypad sheet
└── ui/                      # MainViewModel, pairing screen, theme
app/src/test/                # 394 unit tests (layout/gesture/HID reports/Robolectric UI)
```

See the in-code comments and the [cross-module contract](app/src/main/java/com/pocketkeyboard/app/hid/HidTransport.kt) for details.

## Known Limitations

| # | Limitation | Status |
| --- | --- | --- |
| 1 | **Final pairing confirmation needs the system dialog** | `setPairingConfirmation` / `setPin` require `BLUETOOTH_PRIVILEGED` (blocked for normal apps); the app shows the real SSP number, just tap "Pair" in the system dialog |
| 2 | **Multi-host switching relies on known registry addresses** | Hosts never connected before are not auto-`connectHost`; select them once on the pairing screen |
| 3 | **HID device registration uniqueness** | The system allows only one app to register the HID device at a time; if taken by another IME, registration retries when the app returns to the foreground |
| 4 | **Registration-wedge root assist** | Some ROMs (MIUI observed) wedge HID registration after a force-kill; non-rooted devices get a "toggle Bluetooth" prompt, rooted devices self-heal by restarting the Bluetooth stack |
| 5 | **`AppMode.NUMPAD` does not switch pages** | The keypad is a bottom sheet inside the trackpad page |
| 6 | **Test coverage boundary** | Unit tests cover the pure-logic layer; real Bluetooth connections, gesture feel, and haptic levels need on-device verification; no androidTest yet |

## Permissions

| Permission | Purpose |
| --- | --- |
| `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` / `BLUETOOTH_ADVERTISE` | Android 12+ device discovery and connection |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` | Classic Bluetooth on Android 11 and below |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Required for scanning on Android 11 and below |
| `POST_NOTIFICATIONS` | Connection status notifications on Android 13+ |

## License

[MIT License](LICENSE)
