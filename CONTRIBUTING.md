# Contributing to ScreenSight AI

Thank you for your interest in contributing to **ScreenSight AI**!

## Core Engineering Principles

Before opening an issue or pull request, please ensure your changes adhere to the project's core philosophy:

1. **BYOK Only (Bring Your Own Key)**: Never hardcode developer API keys or commit secrets to Git.
2. **Zero Mandatory Backend**: Keep the application self-contained on Android, connecting directly to Google's official Gemini Live API (`BidiGenerateContent`).
3. **Google Gemini Only**: Do not introduce alternative proprietary AI providers.
4. **Explicit User Control & Privacy**: Screen capture must always use the official Android `MediaProjection` consent dialog and run inside a visible foreground service.
5. **Resource Efficiency**: Avoid blocking the main thread or accumulating unbounded image/audio buffers.

## Pull Request Process

1. Fork the repository and create a feature branch (`git checkout -b feat/your-feature`).
2. Write or update unit and Robolectric tests under `app/src/test/java/com/example/`.
3. Verify compilation and test execution:
   ```bash
   gradle :app:testDebugUnitTest
   gradle :app:assembleDebug
   ```
4. Submit a pull request with a clear description of the changes and device testing results.
