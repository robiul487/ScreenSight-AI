# Changelog

All notable changes to **ScreenSight AI** will be documented in this file.

## [1.0.0] - 2026-11-18

### Added
- **Gemini Live API WebSocket Client**: Full `BidiGenerateContent` implementation supporting bidirectional 16 kHz PCM audio input, 24 kHz PCM audio output, 1 FPS JPEG screen frame streaming, and real-time text turns.
- **BYOK Android Keystore Storage**: Local AES-256-GCM encryption for user-provided Gemini API keys, plus live REST model discovery and key validation.
- **Android 14+ Compliant Screen Capture**: `ScreenCaptureService` foreground service with `MediaProjection.Callback`, `VirtualDisplay`, `ImageReader`, and notification actions.
- **Smart FrameProcessor**: Aspect-ratio-preserving scaling (1280px max dimension), JPEG compression, and 64-bit perceptual luminance duplicate frame skipping.
- **Two-Way Audio Engine**: `AudioCaptureManager` (`AudioRecord` with `AcousticEchoCanceler` and RMS voice activity detection) and `AudioPlaybackManager` (`AudioTrack` with barge-in interruption flushing).
- **Material 3 Jetpack Compose UI**: Home screen, Live Assistant screen with 11 distinct state visualizers, API & Model Settings, Appearance & Voice Settings, and Privacy & Security disclosures.
- **Unit & Robolectric Test Suite**: Comprehensive tests for protocol serialization, FrameProcessor scaling/deduplication, error mapping, API key storage, and Compose UI navigation.
