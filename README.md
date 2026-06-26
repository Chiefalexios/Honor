# HonorGuard — Dialer + Call Recorder for Honor 200

A full **replacement Phone app** for Honor 200 (and any Android 12+ device)
with automatic call recording, spam/scammer detection, and a clean dark UI.

---

## Features

- **Full default dialer** — replaces Honor's built-in phone app
- **Auto-records every call** using `VOICE_COMMUNICATION` audio source
- **Manual record button** during active calls
- **Spam detection** — 3-layer system:
  - Layer 1: Local contacts (always safe)
  - Layer 2: Heuristic rules (short numbers, sequential patterns, etc.)
  - Layer 3: Optional community API lookup
- **Caller ID** from contacts
- **Call log** with spam badges and recording indicators
- **Dialpad** with Indian number formatting
- Amber pulsing "shield ring" on incoming spam calls
- Works on Android 12, 13, 14, 15, 16, 17+

---

## Build Instructions

### Requirements
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 35

### Steps

```bash
# 1. Clone / open the project in Android Studio
# 2. Let Gradle sync

# 3. Build APK
./gradlew assembleDebug

# 4. Install via ADB (phone connected with USB debugging on)
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or use **GitHub Actions** (your usual build method):
- Push to your repo
- The `.github/workflows/build.yml` file handles the rest
- Download the APK from the Actions artifacts tab
- Sideload via ADB

---

## First-Time Setup on Honor 200

1. **Install the APK** via ADB sideload
2. **Open HonorGuard** — it will immediately prompt you to set it as the default phone app
3. Tap **"Set as default"** in the system dialog
4. Grant all requested permissions:
   - Phone (for call control)
   - Microphone (for recording)
   - Contacts (for caller ID)
   - Notifications
5. Make or receive a test call — recording starts automatically

---

## Call Recording Notes — Snapdragon 7 Gen 3 (SM7550-AB)

### Hardware context
Honor 200 uses **Qualcomm SM7550-AB** with the **Aqstic WCD audio codec**
(WCD9380 family) and Qualcomm's audio HAL — not HiSilicon/Kirin.
This matters significantly for call recording.

### The three recording paths (tried automatically in order)

**Path 1 — MediaRecorder / VOICE_COMMUNICATION** *(best outcome)*
When set as default dialer, MagicOS grants elevated telephony trust.
On SM7550 + MagicOS 8.x (Android 14), this may capture both call legs
via Qualcomm's voice processing chain. Whether both sides come through
depends on your exact firmware. The notification shows
`MediaRecorder/VOICE_COMM` if this path succeeds.

**Path 2 — Dual AudioRecord stereo mix** *(reliable fallback)*
Two simultaneous `AudioRecord` instances:
- Left channel: `AudioSource.MIC` → your voice (always captured)
- Right channel: `AudioSource.VOICE_COMMUNICATION` → caller (best effort)

Encoded into a **stereo AAC M4A** via `MediaCodec + MediaMuxer`.
Pan left in any audio player to hear yourself, right for the caller.
Notification shows `DualAudioRecord (L=mic, R=caller)`.

**Path 3 — Single MIC** *(always works)*
Your microphone only. Still very useful for scammer evidence — your
responses make the context clear. Shows `MIC only (uplink)`.

### Why 16000 Hz (not 44100 Hz)
The Qualcomm Aqstic WCD9380 voice chain runs natively at 16 kHz.
Requesting 44100 Hz on voice audio sources triggers ADSP resampling
which causes artifacts and can make the audio HAL reject the request
entirely. 16 kHz is also the standard for voice intelligibility
(used by courts, banks, and call centres worldwide).

### Where recordings are saved
```
/Android/data/com.honorguard/files/Recordings/
CALL_20240115_143022_919876543210.m4a
```
Format: AAC 128kbps in M4A container. Plays in any media player.

### To access recordings
- File manager → Internal storage → Android → data → com.honorguard → files → Recordings
- Or: Settings → Apps → HonorGuard → Storage → Files

---

## Spam Detection

### How it works
1. **Saved contacts** → always marked SAFE, no further checks
2. **Heuristic rules** (offline, instant):
   - 3-5 digit numbers → SUSPECTED (service/promo lines)
   - All same digit (1111111111) → SPAM
   - Sequential digits (1234567890) → SUSPECTED
   - Unusual international prefix → SUSPECTED
3. **Community API** (optional, requires internet):
   - Configurable endpoint in `SpamRepository.kt`
   - Number is SHA-256 hashed before sending — actual number never transmitted

### Spam levels
| Level | Color | Meaning |
|-------|-------|---------|
| SAFE | Green | Saved contact |
| UNKNOWN | Grey | No data found |
| SUSPECTED | Amber | Possible spam |
| SPAM | Orange | Community-confirmed spam |
| FRAUD | Red | Confirmed scam/fraud |

---

## Architecture

```
HonorGuardApp
├── GuardInCallService      ← Android Telecom bridge (InCallService)
├── CallRecordingService    ← Foreground service for audio capture
│
├── data/
│   ├── GuardDatabase       ← Room database (call_log table)
│   ├── model/Models.kt     ← CallRecord, ActiveCallState, SpamScore
│   └── repository/
│       └── SpamRepository  ← Contact lookup + heuristics + API
│
└── ui/
    ├── GuardViewModel      ← Shared state (call state machine, duration)
    ├── theme/Theme.kt      ← Navy/steel Compose theme
    └── screens/
        ├── MainActivity    ← Tabs: Recents | Dialpad | Contacts
        └── InCallActivity  ← Active call screen with controls
```

---

## GitHub Actions Build (your workflow)

Create `.github/workflows/build.yml`:

```yaml
name: Build HonorGuard APK

on:
  push:
    branches: [ main ]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'

      - name: Build APK
        run: ./gradlew assembleDebug

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: HonorGuard-debug
          path: app/build/outputs/apk/debug/app-debug.apk
```

---

## Adding a Real Spam API

Edit `SpamRepository.kt` → `apiCheck()` function.
Recommended free options:

1. **NumVerify** — 250 free lookups/month, spam flag available
2. **Abstract API Phone Validation** — free tier available
3. **Self-hosted** using open-source spam databases (OpenCNAM, etc.)

Add your API key to `local.properties` (never commit this file):
```
spam_api_key=your_key_here
```

Then read it in `build.gradle`:
```groovy
buildConfigField "String", "SPAM_API_KEY",
    "\"${localProps.getProperty('spam_api_key', '')}\""
```

---

## Legal Note

Call recording laws vary by country. In India, recording your own calls
for personal protection from fraud is generally permitted. Always inform
the other party if required by local law. This app is intended for
personal safety against scammers.
