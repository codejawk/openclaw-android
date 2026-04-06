# OpenClaw Native Android

**OpenClaw running natively on Android — zero Termux, zero proot, zero root required.**

A complete Android Studio project (Kotlin + Jetpack Compose, minSdk 31) that runs the
[OpenClaw](https://github.com/openclaw) AI gateway as a native Android foreground service.

---

## Why This Exists

OpenClaw normally requires Termux + proot-distro (a full Ubuntu container) to run on Android.
This project eliminates all that by fixing the 4 root causes:

| Problem | Fix |
|---|---|
| `/tmp` is read-only | `TMPDIR` → `context.filesDir/tmp` |
| `os.networkInterfaces()` crashes on Bionic | `bionic-bypass.js` injected via `NODE_OPTIONS` |
| `koffi` needs a C++ toolchain | Cross-compiled on PC with Android NDK, bundled in APK |
| Hardcoded `/bin/sh`, `/usr/bin/env` | Patched to `/system/bin/sh` at install time |

---

## Project Structure

```
openclaw-native/
├── app/src/main/
│   ├── AndroidManifest.xml          — all permissions declared
│   ├── assets/
│   │   ├── node-arm64               ← pre-built Node.js 22 ARM64 binary (you provide)
│   │   ├── koffi.node               ← cross-compiled koffi addon (script provides)
│   │   ├── openclaw-bundle/         ← openclaw npm package (script provides)
│   │   ├── bionic-bypass.js         ← os.networkInterfaces() crash fix
│   │   └── path-patch.sh            ← /bin/sh → /system/bin/sh patcher
│   └── java/com/openclaw/native_app/
│       ├── OpenClawApp.kt           — Hilt application class
│       ├── MainActivity.kt          — Compose nav host + theme
│       ├── MainViewModel.kt         — all UI state + gateway lifecycle
│       ├── GatewayService.kt        — foreground service + WakeLock
│       ├── BootstrapManager.kt      — first-run asset extraction
│       ├── NodeRunner.kt            — Node.js process lifecycle + watchdog
│       ├── AndroidNodeProvider.kt   — 15 device commands via native APIs
│       ├── TokenTracker.kt          — token counting + cost calculation
│       ├── OllamaClient.kt          — local model management
│       ├── VoiceManager.kt          — wake word + TTS
│       ├── NotificationInterceptService.kt
│       ├── BootReceiver.kt
│       ├── SamsungBatteryReceiver.kt
│       ├── data/db/                 — Room database (TokenEntity, TokenDao)
│       ├── di/AppModule.kt          — Hilt DI
│       └── ui/screens/              — 7 Compose screens
├── scripts/
│   ├── get-node-binary.sh           — download Node.js ARM64 binary
│   ├── compile-koffi-android.sh     — cross-compile koffi with NDK
│   └── bundle-openclaw.sh           — pack openclaw npm into assets
└── README.md
```

---

## Build Instructions

### Prerequisites (on your PC)

- Android Studio Ladybug (2024.x) or newer
- Android NDK r26+ (`sdkmanager "ndk;26.1.10909125"`)
- Node.js 22 + npm on your PC
- ADB connected to your Samsung Galaxy

### Step 1 — Get the Node.js ARM64 binary

```bash
bash scripts/get-node-binary.sh
```

This downloads a pre-built Node.js 22 ARM64 Android binary from the
[nodejs-mobile](https://github.com/nodejs-mobile/nodejs-mobile/releases) project
and places it at `app/src/main/assets/node-arm64`.

> **Manually:** Download the binary yourself from the link above and copy it to
> `app/src/main/assets/node-arm64`.

### Step 2 — Cross-compile koffi for Android ARM64

```bash
bash scripts/compile-koffi-android.sh
```

Uses the Android NDK Clang toolchain to compile `koffi.node` for `aarch64-linux-android31`.
Output: `app/src/main/assets/koffi.node`

### Step 3 — Bundle the openclaw npm package into assets

```bash
bash scripts/bundle-openclaw.sh
```

Installs `openclaw` from npm (production deps only), copies it to
`app/src/main/assets/openclaw-bundle/`, and strips source maps to reduce APK size.

If you have a local openclaw checkout:

```bash
bash scripts/bundle-openclaw.sh --local /path/to/openclaw
```

### Step 4 — Build the APK

```bash
./gradlew assembleDebug
```

Or open the project in Android Studio and press ▶ Run.

### Step 5 — Install to your phone

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## First Launch

On first launch, `BootstrapManager` extracts all assets to the app's private storage
(`/data/user/0/com.openclaw.native_app/files/`) — this takes 5–15 seconds.

The app will ask for permissions. Grant them all for full functionality.

**Critical for Samsung Galaxy:** Go to **Settings → Apps → OpenClaw → Battery → Unrestricted**
to prevent Samsung One UI from killing the gateway. The app will prompt you automatically.

---

## Features

### AI Providers
Configure in Settings tab:
- **Anthropic Claude** (claude-sonnet-4-20250514) — recommended
- **Ollama** (local models: llama3, mistral, phi3, gemma2, etc.)
- **OpenAI** (GPT-4o, GPT-4o-mini)
- **Google Gemini** (1.5 Pro, 1.5 Flash)
- **OpenRouter** (access 100+ models)

### Device Commands (15 commands via WebSocket node protocol)

| Command | Android API |
|---|---|
| `camera.capture` | CameraX (back lens) |
| `camera.front` | CameraX (front lens) |
| `location.get` | FusedLocationProviderClient |
| `calendar.list` | ContentResolver + CalendarContract |
| `calendar.create` | ContentResolver insert |
| `contacts.list` | ContactsContract |
| `sms.send` | SmsManager |
| `sms.list` | Telephony.Sms |
| `notifications.list` | NotificationListenerService |
| `notifications.send` | NotificationManager |
| `screen.capture` | MediaProjection |
| `media.photos` | MediaStore |
| `audio.record` | MediaRecorder |
| `haptic.vibrate` | VibrationEffect |
| `app.launch` | PackageManager + Intent |

### Token Tracking

Every API call is tracked: input tokens, output tokens, cost in USD.
Pricing uses official rates (Claude Sonnet: $3/M in, $15/M out; Ollama: free).
Export as CSV from the Tokens tab.

### Voice (Wake Word)
Say **"Hey OpenClaw"** — the gateway responds via TTS.
Runs fully offline using Android SpeechRecognizer + TextToSpeech.
Optional: set an ElevenLabs API key in Settings for higher quality voice.

### Channels
WhatsApp, Telegram, Slack, Discord, SMS — all run inside the Node.js gateway process.
Configure via the Channels tab (opens the gateway's built-in web UI).

---

## Samsung Galaxy (One UI) Notes

Samsung's battery management is aggressive. The app handles this with:

1. **WakeLock** (`PARTIAL_WAKE_LOCK`) — prevents CPU sleep
2. **Battery optimization exemption** — requested on first launch
3. **Samsung Smart Manager broadcast receiver** — restarts service if killed
4. **`stopWithTask=false`** — service survives app removal from recents
5. **mDNS disabled** in config — prevents Samsung network stack crashes

For best reliability: **Device Care → Battery → App power management → Add OpenClaw to "Apps that won't be put to sleep"**.

---

## Token Counting Architecture

The `TokenTracker` intercepts usage in two ways:

1. **Log parsing** — `NodeRunner` streams Node.js stdout; lines matching `[usage]` are parsed
2. **Direct calls** — `AndroidNodeProvider` calls `tokenTracker.record()` after each proxied API call

OpenClaw emits standardized usage log lines:
```
[usage] provider=anthropic model=claude-sonnet-4-20250514 in=1234 out=567
```

---

## Local Models with Ollama

1. Install [Ollama](https://ollama.ai) on a PC or server on your local network
2. Set the Ollama endpoint in Settings (e.g., `http://192.168.1.100:11434`)
3. Select "Ollama" as provider and choose your model
4. Tap "Start Gateway" — all inference goes to your local Ollama instance, zero cloud cost

For on-device inference (experimental): Ollama has an Android APK in development.
When available, set endpoint to `http://localhost:11434`.

---

## License

MIT — same as OpenClaw.
