# ScreenSight AI (featuring Neya Native Assistant)

**Open-Source Autonomous Android Mobile Agent Command Hub, Hybrid Gemini Live + Gemma 4 Engine & Native Digital Assistant**

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Platform](https://img.shields.io/badge/Platform-Android_24%2B-38BDF8.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack_Compose-818CF8.svg)](https://kotlinlang.org)
[![Architecture](https://img.shields.io/badge/Architecture-MVVM_%2B_Room_%2B_BYOK-10B981.svg)](#5-architecture)

---

## 1. Overview & Evolution

**ScreenSight AI** began as an open-source Gemini Live screen-sharing assistant and has evolved into a full **Autonomous Mobile Agent Command Hub** and **Native Android Digital Assistant (`Neya`)** — running locally on Android with **zero developer backend**, **zero mandatory accounts**, and **zero telemetry**.

Your device connects directly to Google's official Generative Language APIs using your own **Android Keystore (AES-256-GCM)** encrypted API key (BYOK), combining:

- **Real-time Multimodal Voice & Optional Screen Vision** via **Gemini Live API (`BidiGenerateContent`)**
- **Structured Multi-Step Planning & Deep Reasoning** via **Gemma 4 (`models/gemma-4-31b-it` / `models/gemma-4-26b-a4b-it`)** with automatic Gemini Text fallback
- **On-Device Autonomous Execution** via Android Accessibility, App Launching, Alarms, Safe Web Automation, WorkManager Schedules, and Local Room v3 Memory & Skills
- **Native Android Assistant Registration (`Neya`)** via `VoiceInteractionService`, `VoiceInteractionSession`, `RoleManager.ROLE_ASSISTANT`, and `ACTION_ASSIST`

> **Naming Note:** The project and Android launcher currently retain the `ScreenSight AI` brand name while the integrated Android system digital assistant surface is named **Neya (`Neya (ScreenSight AI)`)**.

---

## 2. Core Experience & 5-Tab Navigation

ScreenSight AI is organized into a clean Material 3 **5-tab workspace** plus a navigation drawer for specialized tools:

1. **Home — Smart Command Hub**
   - **Actionable Agent Readiness**: Real-time readiness status with one-tap fix chips for API Key, Accessibility Control, Microphone, and Floating Overlay permissions.
   - **Autonomy Level Switcher**: Switch live between **Ask First** (confirms every device-changing action), **Balanced** (runs safe actions automatically, confirms sensitive ones), and **Family Easy Mode** (Bangla-first voice guidance with maximum safeguards).
   - **Native Neya Assistant Pill**: Live verification of Android's Default Digital Assistant role status linking directly to Assistant setup.
   - **Unified Command Input**: Type any natural-language goal or instruction — forwards to Gemini Live when active, or launches an instant local/Gemma multi-step mission when idle.
   - **Runnable Skills & Customizable Agent Missions**: Launch Room-backed reusable skills or parameterized missions (Open App, Set Custom Alarm, Read Screen, Check Time), view Proactive Suggestions, and launch the **Floating Agent Overlay** pill in one tap.

2. **Agent — Dual-Mode Agent Console**
   - **Live Multimodal Mode (Voice + Optional Screen)**: Full-duplex 16 kHz PCM microphone input + 24 kHz PCM spoken output with acoustic echo suppression, barge-in interruption, custom Compose Canvas live state visualizer (`IDLE`, `CONNECTING`, `CONNECTED`, `SHARING_SCREEN`, `LISTENING`, `THINKING`, `SPEAKING`, `RECONNECTING`, `ERROR`, `STOPPED`), and on-demand 1 FPS JPEG screen vision (explicitly consented via `MediaProjection`).
   - **Instant Autonomous Text & Reasoning Mode (No Live Session Required)**: Send commands or questions directly while Gemini Live is idle — executes locally via `AgentCoordinator`, `MultiAgentOrchestrator`, and `GemmaAgentReasoningEngine` without opening a WebSocket or forcing screen sharing.
   - **4-Pane Live Telemetry**: Switch between **Transcript**, **Agent Activity** (step progress + interactive safety confirmation cards), **Task Logs**, and **Diagnostics** (Accessibility UI snapshots, action verification, checkpoint recovery, and test harness).

3. **Tasks — Mission & Schedule Management**
   - **Multi-Stage Goal Orchestrator**: Plan and run DAG-structured autonomous goals with live progress, Pause, Resume, Retry, Cancel, and Delete controls.
   - **Checkpoints & Recovery**: Persist task state in local Room SQLite so interrupted missions can be inspected and resumed safely without auto-replaying sensitive actions.
   - **Recurring Schedules**: User-approved background automation powered by Android `WorkManager` (`AgentTaskScheduler`) with boot persistence.
   - **Model Usage Telemetry**: Inspect whether each mission was planned via Gemma 4, Gemini Fallback, or Local Planner.

4. **Library — On-Device Memory & Reusable Skills**
   - **Local Long-Term Memory**: Strictly on-device Room storage with category filtering, retention policies, starter templates, and `MemoryPrivacyGuard` filtering out secrets, passwords, OTPs, and financial credentials.
   - **Reusable Skills**: Create, edit, enable/disable, approve, and run parameterized multi-step workflows (`AgentSkillRepository` + `SkillLearningEngine`).

5. **Settings — 12-Category Searchable Hub**
   - Instant keyword search across **General**, **Voice & Audio** (including **Neya Native Assistant & Hey Neya**), **Screen Awareness**, **Notifications**, **AI & Model Settings** (Gemini Live + Gemma 4 Reasoning), **Agent Behavior**, **Memory & Privacy**, **Skills & Automation**, **Permissions & Device Access**, **Storage & Data**, **Security**, and **About & Support**.

### Specialized Secondary Screens (via Navigation Drawer & Quick Links)
- **Family Easy Mode**: High-contrast, Bangla-first voice companion with simplified actions and strict safety confirmation prompts.
- **Autonomous Agent Hub**: Dynamic tool catalog, tool reliability metrics, and self-reflection reports.
- **Safe Web Browser**: Guarded in-app WebView automation with domain/action safety checks.
- **Trusted Family Contacts**: Curated on-device contact list for safe calling workflows.

---

## 3. Hybrid Multi-Model AI Architecture (Single BYOK Credential)

ScreenSight AI uses a **single encrypted Google AI Studio API key** across both real-time multimodal and structured reasoning workloads:

| Workload | Protocol | Default Model ID | Fallbacks |
| :--- | :--- | :--- | :--- |
| **Live Voice & Screen Vision** | Gemini Live WebSocket (`BidiGenerateContent`) | `models/gemini-3.1-flash-live-preview` | `models/gemini-2.5-flash-native-audio-preview-12-2025`, `models/gemini-2.5-flash-native-audio-preview-09-2025` |
| **Deep Planning & Reasoning** | Google GenAI REST (`generateContent`) | `models/gemma-4-31b-it` | `models/gemma-4-26b-a4b-it`, `models/gemini-2.5-flash` (auto-fallback) |
| **Deterministic Fast Actions** | On-Device Local Planner | Zero cloud call required | Direct `AgentToolRegistry` execution |

Routing modes (`Auto Complex`, `Always Gemma Reasoning`, `Local Only`) and model validation (`GET /v1beta/models`) are configurable in **Settings → AI & Model Settings**.

---

## 4. Native Android Digital Assistant (`Neya`) & `"Hey Neya"` Capability

ScreenSight AI registers **Neya** using Android's official VoiceInteraction framework:

- **Registered System Components**:
  - `NeyaVoiceInteractionService` (`BIND_VOICE_INTERACTION`, `@xml/neya_voice_interaction_service`)
  - `NeyaVoiceInteractionSessionService` (`BIND_VOICE_INTERACTION`)
  - `NeyaVoiceInteractionSession` (lightweight bottom-sheet overlay wired directly to the single shared `AppContainer` — zero duplicate coordinators, zero duplicate mic/WebSocket streams, never auto-starts `MediaProjection`, and never cancels unrelated background tasks on hide)
  - `NeyaRecognitionService` (AOSP `VoiceInteractionServiceInfo` compatibility without background mic loops)
  - `MainActivity` `ACTION_ASSIST` intent filter
- **Real Role Verification**: Uses `RoleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)` on Android 10+ (API 29+) with `Settings.ACTION_VOICE_INPUT_SETTINGS` fallback on API 24–28. Always re-verifies real system role state on return and `ON_RESUME`.
- **Honest `"Hey Neya"` Wake-Word Policy**:
  - **Active Trigger**: Manual system assistant gestures (power/home long-press, corner swipe, assist shortcut) and in-app controls.
  - **Hardware DSP (`AlwaysOnHotwordDetector` / `SoundTrigger`)**: Honestly reported as **Unavailable (OEM Privileged Only)** on standard third-party installs because Android restricts `CAPTURE_AUDIO_HOTWORD` and `MANAGE_VOICE_KEYPHRASES` to privileged preinstalled system apps.
  - **Software Fallback**: Honestly reported as **Unavailable (No Bundled Wake Model)** — no offline neural `"Hey Neya"` model is bundled in this APK, and continuous cloud audio streaming, `SpeechRecognizer` loops, and permanent background microphone services are strictly disabled to protect battery and privacy.

---

## 5. Architecture

```text
ANDROID APPLICATION (MVVM + Clean Modular Architecture, Single Process)
|
|-- Presentation Layer (com.example.ui)
|     |-- HomeScreen (Smart Command Hub)
|     |-- LiveAssistantScreen (Dual-Mode Agent Console)
|     |-- TasksScreen & AutonomousHubScreen (Missions, Schedules, Reflection)
|     |-- LibraryScreen & MemorySkillsScreen (Local Memory & Skills)
|     |-- SettingsHubScreen, SettingsCategoryScreen, ApiSettingsScreen
|     |-- FamilyEasyModeScreen, SafeBrowserScreen, TrustedContactsScreen
|     |-- Components (NeyaAssistantSettingsSection, Status/Cards, Dialogs)
|     |-- ScreenSightViewModel
|
|-- Native Android Assistant Layer (com.example.assistant)
|     |-- NeyaAssistantController (RoleManager + Capability Audit + Engine Bridge)
|     |-- NeyaVoiceInteractionService & NeyaVoiceInteractionSessionService
|     |-- NeyaVoiceInteractionSession & NeyaRecognitionService
|
|-- Autonomous Agent Engine (com.example.agent)
|     |-- AgentCoordinator, MultiAgentOrchestrator, LongTermTaskOrchestrator
|     |-- GemmaAgentReasoningEngine, DynamicToolManager, SelfReflectionEngine
|     |-- AgentToolRegistry, AgentActionExecutor, AgentAccessibilityService
|     |-- AgentSafetyManager, MemoryPrivacyGuard, SkillLearningEngine
|     |-- ProactiveAssistantEngine, SafeWebAutomationManager, BanglaVoiceFeedbackManager
|
|-- AI & Local Data Layer (com.example.data)
|     |-- AIModelRouter, GemmaReasoningProvider, GeminiTextProvider, GoogleGenAiRestClient
|     |-- GeminiLiveRepository, GeminiWebSocketClient, GeminiLiveProtocol
|     |-- Room v3 Database (Checkpoints, LongTermTasks, Schedules, Memories, Skills, Metrics)
|     |-- ApiKeyRepository (Android Keystore AES-256-GCM) & SettingsRepository (DataStore)
|
|-- Android Services & Audio/Vision Pipeline (com.example.service)
      |-- SessionManager, ScreenCaptureService (MediaProjection|Microphone FGS)
      |-- FloatingOverlayController, FrameProcessor (1 FPS JPEG + Perceptual Dedupe)
      |-- AudioCaptureManager (16 kHz PCM + AEC + Echo Suppression) & AudioPlaybackManager (24 kHz PCM)
      |-- AgentTaskScheduler & AgentBackgroundWorker (WorkManager)
```

---

## 6. Technology Stack

| Component | Technology |
| :--- | :--- |
| Language & UI | Kotlin, Jetpack Compose (Material 3, Edge-to-Edge) |
| Navigation | Navigation Compose (5-Tab Bottom Bar + Navigation Drawer) |
| Real-Time Multimodal AI | Google Gemini Live API (`BidiGenerateContent` WSS via OkHttp) |
| Structured Reasoning AI | Google Gemma 4 (`generateContent` REST via OkHttp) + Gemini Text Fallback |
| Native System Assistant | Android `VoiceInteractionService`, `VoiceInteractionSession`, `RoleManager` |
| Device Automation | Android `AccessibilityService`, `MediaProjection`, `SYSTEM_ALERT_WINDOW` |
| Local Persistence | Room v3 SQLite (KSP) + Jetpack DataStore Preferences |
| Background Scheduling | AndroidX WorkManager + Boot Receiver |
| Audio Pipeline | `AudioRecord` (16 kHz PCM + Hardware AEC/NS) & `AudioTrack` (24 kHz PCM) |
| Secret Protection | Android Keystore (`AES/GCM/NoPadding`, 256-bit) |
| Serialization & Async | Kotlinx Serialization JSON, Kotlin Coroutines, StateFlow / SharedFlow |
| Automated Testing | JUnit 4, Robolectric, Jetpack Compose UI Test |

---

## 7. Requirements & Setup

- **Android Device**: Android 7.0 (API 24) or higher (Android 12–16 recommended for full `RoleManager`, `MediaProjection`, and Material 3 features).
- **Google AI Studio API Key**: Obtain a free or paid BYOK key from [Google AI Studio](https://aistudio.google.com/app/apikey).
- **No Hardcoded Secrets**: API keys are entered at runtime inside the app and encrypted with Android Keystore. Never commit API keys to source control.

### Build & Run
1. Open the repository in **Android Studio** (Ladybug or newer) or use Gradle CLI:
   ```bash
   gradle :app:assembleDebug
   ```
   Output APK: `app/build/outputs/apk/debug/app-debug.apk`
2. Configure your API key in **Settings → AI & Model Settings**, tap **Validate** to inspect Live & Gemma 4 models available on your key, and tap **Save Key**.
3. Optionally grant **Accessibility**, **Microphone**, **Overlay**, and set **Neya** as your **Default Digital Assistant** in **Settings → Voice & Audio**.

### Running Automated Tests
```bash
gradle :app:testDebugUnitTest
```
Includes unit, Robolectric, Room, security, echo-suppression, Gemma 4 integration, UI navigation, and native Android Assistant tests (`NeyaNativeAssistantTest`, `GemmaModelIntegrationTest`, `AudioEchoSuppressionTest`, `AgentExecutionEngineTest`, `Phase3ReliabilityTest`, `Phase4MemorySkillTest`, `Phase5AutonomousAgentTest`, `Phase6UiNavigationSettingsTest`, `Phase7AgentCommandHubTest`).

---

## 8. Privacy, Safety & Known Limitations

- **Zero Developer Backend**: All memories, skills, task checkpoints, and preferences remain strictly on-device. Only user-initiated prompts, active voice/screen frames during a Live session, or explicit Gemma reasoning queries are sent directly to Google's APIs.
- **Non-Bypassable Safety Guardrails**: `AgentSafetyManager` and `MemoryPrivacyGuard` block automatic password entry, OTP scraping, financial/banking transfers, and unauthorized destructive actions across both in-app and native assistant invocations.
- **Third-Party Wake-Word Limitation**: Android does not grant `CAPTURE_AUDIO_HOTWORD` or SoundTrigger DSP access to third-party apps; `"Hey Neya"` stays cleanly in Manual System Assistant mode without fake listening indicators or hidden microphone polling.
- **1 FPS Screen Vision**: Screen sharing is opt-in per session via Android `MediaProjection` and transmits compressed 1 FPS JPEG frames with static-frame skipping.
- **Physical Device Validation**: Hardware acoustic echo cancellation, `MediaProjection` cross-app switching, Android Accessibility gestures, and OS Default Assistant picker flows require physical Android handset verification beyond Robolectric JVM tests.

---

## 9. Documentation, Contributing & License

- Privacy Policy: [PRIVACY.md](PRIVACY.md)
- Security Model: [SECURITY.md](SECURITY.md)
- Changelog: [CHANGELOG.md](CHANGELOG.md)
- Contributing Guide: [CONTRIBUTING.md](CONTRIBUTING.md)
- License: **Apache License, Version 2.0** ([LICENSE](LICENSE))
