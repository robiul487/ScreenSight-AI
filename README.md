# ScreenSight AI

**Open-Source Gemini Live Screen Sharing & Real-Time Voice Assistant for Android**

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Platform](https://img.shields.io/badge/Platform-Android_24%2B-38BDF8.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack_Compose-818CF8.svg)](https://kotlinlang.org)

---

## 1. Project Introduction

**ScreenSight AI** (`screensight-ai`) is a free, open-source, Bring-Your-Own-Key (BYOK) Android application that enables real-time screen sharing and natural two-way voice conversations with Google's **Gemini Live API** (`BidiGenerateContent` over Secure WebSockets).

Unlike proprietary assistants or cloud-proxied wrappers, ScreenSight AI operates with **zero developer backend**, **zero mandatory account registration**, and **zero telemetry**. Your Android device connects directly to Google's official Gemini Live WebSocket endpoint using your own Android Keystore-encrypted API key.

---

## 2. Screenshots & Visual Overview

- **Home Console**: Displays real-time connection readiness, masked Keystore API key status, active Gemini Live model, last session metrics, and a prominent **Start Live Session** action.
- **Live Assistant Screen**: Dedicated real-time voice and screen-sharing cockpit with custom Canvas state visualizers (`IDLE`, `CONNECTING`, `CONNECTED`, `REQUESTING_SCREEN_PERMISSION`, `SHARING_SCREEN`, `LISTENING`, `THINKING`, `SPEAKING`, `RECONNECTING`, `ERROR`, `STOPPED`), live microphone RMS reactivity, network backpressure indicator, and interactive transcript.
- **API & Model Settings**: BYOK API key management with Android Keystore AES-256-GCM encryption, live REST endpoint key validation, verified BidiGenerateContent model selector, and custom model ID entry.
- **Appearance & FrameProcessor Settings**: Dark/Light/System themes, Material You dynamic color, prebuilt Gemini voice selection (`Aoede`, `Puck`, `Charon`, `Kore`, `Fenrir`), response language preference, JPEG compression tuning, and customizable system instructions.

---

## 3. Features

- **Real-Time Gemini Live WebSocket Integration**: Direct `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent` implementation using OkHttp.
- **Native Android Screen Capture**: Uses `MediaProjectionManager`, `VirtualDisplay`, and `ImageReader` inside an Android 14+ compliant `ForegroundService` (`mediaProjection|microphone`).
- **Smart 1 FPS FrameProcessor**: Resizes screen frames preserving aspect ratio (default max dimension `1280px`), compresses to JPEG (default quality `70`), computes a 64-bit perceptual luminance signature to skip redundant static frames, and recycles bitmaps immediately to avoid memory leaks.
- **Two-Way Low-Latency Voice**:
  - **Input**: Android `AudioRecord` at 16 kHz, 16-bit PCM mono little-endian with hardware `AcousticEchoCanceler` and `NoiseSuppressor`.
  - **Output**: Android `AudioTrack` at 24 kHz, 16-bit PCM mono with `AudioFocusRequest` and instant barge-in interruption flushing.
- **BYOK Hardware-Backed Encryption**: Protects the user's Gemini API key at rest using `AndroidKeyStore` (`AES/GCM/NoPadding`, 256-bit key).
- **Network Backpressure Protection**: Monitors WebSocket outbound queue size and drops stale video frames automatically when the network connection slows down.

---

## 4. Architecture

```text
ANDROID APPLICATION (MVVM + Clean Architecture)
|
|-- Presentation Layer (com.example.ui)
|     |-- HomeScreen
|     |-- LiveAssistantScreen
|     |-- ApiSettingsScreen
|     |-- AppearanceSettingsScreen
|     |-- PrivacySecurityScreen
|     |-- ScreenSightViewModel
|
|-- Domain Layer (com.example.domain)
|     |-- StartSessionUseCase
|     |-- StopSessionUseCase
|     |-- SendScreenFrameUseCase
|     |-- StartAudioUseCase
|     |-- SendTextMessageUseCase
|     |-- ScreenSightModels & ScreenSightError
|
|-- Data Layer (com.example.data)
|     |-- GeminiLiveRepository
|     |-- GeminiWebSocketClient
|     |-- GeminiLiveProtocol & GeminiLiveConfigBuilder
|     |-- ApiKeyRepository (Android Keystore AES-256-GCM)
|     |-- SettingsRepository (Jetpack DataStore Preferences)
|
|-- Android Services & Media Engine (com.example.service)
      |-- ScreenCaptureService (ForegroundService + MediaProjection)
      |-- FrameProcessor (Aspect-ratio scaling + Perceptual duplicate filter)
      |-- AudioCaptureManager (AudioRecord 16 kHz PCM + AEC + VAD)
      |-- AudioPlaybackManager (AudioTrack 24 kHz PCM + Interruption flush)
      |-- SessionManager
```

---

## 5. Technology Stack

| Component | Technology |
| :--- | :--- |
| Programming Language | Kotlin |
| Android UI | Jetpack Compose (Material 3) |
| Architecture | MVVM + Clean Architecture |
| Screen Capture | Android `MediaProjection` + `VirtualDisplay` + `ImageReader` |
| Background Operation | Android `ForegroundService` (`mediaProjection`) |
| AI Provider | Google Gemini Live API (`BidiGenerateContent`) |
| Network Protocol | Secure WebSocket (WSS) via OkHttp |
| JSON Serialization | Kotlinx Serialization JSON |
| Audio Recording | Android `AudioRecord` (16 kHz 16-bit PCM) |
| Audio Playback | Android `AudioTrack` (24 kHz 16-bit PCM) |
| Local Preferences | Jetpack DataStore Preferences |
| Secret Storage | Android Keystore (`AES/GCM/NoPadding`) |
| Asynchronous Processing | Kotlin Coroutines + StateFlow / SharedFlow |
| Testing | JUnit 4, Robolectric, Compose UI Test |

---

## 6. Requirements

- **Android Device or Emulator**: Android 7.0 (API 24) or higher (Android 12+ recommended; fully compliant with Android 14/15/16 `MediaProjection` rules).
- **Gemini API Key**: A valid API key from [Google AI Studio](https://aistudio.google.com/app/apikey).
- **Microphone & Internet Access**: Required for real-time voice streaming.

---

## 7. Installation & Android Studio Setup

1. Clone the repository:
   ```bash
   git clone https://github.com/open-source/screensight-ai.git
   cd screensight-ai
   ```
2. Open the project folder in **Android Studio** (Ladybug or newer).
3. Allow Gradle to sync dependencies. No developer API key is needed in `.env` or `local.properties` because the app uses runtime BYOK configuration.
4. Connect a physical Android device (recommended for real microphone + screen projection testing) and click **Run**.

---

## 8. How to Obtain & Configure a Gemini API Key

1. Visit [Google AI Studio](https://aistudio.google.com/app/apikey) and sign in with your Google account.
2. Click **Create API Key** and copy the generated key (`AIza...`).
3. Open **ScreenSight AI** on your Android device.
4. Tap **Configure API Key** (or the gear icon in the top bar).
5. Paste your key into the **Google Gemini API Key (BYOK)** field.
6. Tap **Validate** to verify your key and discover supported `BidiGenerateContent` Live models, or tap **Save Key** to encrypt and store it locally via Android Keystore.

---

## 9. How to Build the APK

To build a debug APK via Gradle CLI:

```bash
gradle :app:assembleDebug
```

The resulting installable APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

To run the unit and Robolectric integration test suite:

```bash
gradle :app:testDebugUnitTest
```

---

## 10. Troubleshooting

- **"Your Gemini API key is invalid"**: Double-check that your key has no leading/trailing spaces and is active in Google AI Studio.
- **"Your selected model does not support Gemini Live API"**: Not all Gemini models in the standard REST API support `BidiGenerateContent`. Select one of the verified Live models (such as `models/gemini-2.0-flash-live-001` or `models/gemini-2.5-flash-native-audio-preview-09-2025`) or tap **Validate** in API Settings to inspect which Live models your key can access.
- **"Your API quota has been reached"**: Check your Google AI Studio rate limits and billing tier.
- **"Screen sharing was stopped by Android"**: Modern Android versions (Android 14+) revoke `MediaProjection` tokens after each capture session or when stopped from the system status bar chip. Tap **Share Screen** again to grant a fresh projection token.

---

## 11. Known Limitations

- **1 FPS Screen Vision**: In accordance with Gemini Live API specifications and mobile bandwidth efficiency, screen sharing transmits compressed JPEG frames at 1 FPS rather than 60 FPS video.
- **Client-Side Key Storage**: While your API key is encrypted at rest using Android Keystore AES-256-GCM, any client-side application could theoretically have runtime secrets extracted on a rooted or compromised device. Only use an API key you control.
- **Physical Device Validation**: Audio echo cancellation and multi-app MediaProjection switching are best validated on a physical Android handset.

---

## 12. Privacy Information

See [PRIVACY.md](PRIVACY.md) and [SECURITY.md](SECURITY.md) for complete details on how screen frames, voice audio, and API keys are handled.

---

## 13. Contributing & License

Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) before submitting a pull request.

Licensed under the **Apache License, Version 2.0**. See [LICENSE](LICENSE) for full text.
