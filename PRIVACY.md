# Privacy Policy

ScreenSight AI is designed with local-first transparency and explicit user control.

## Summary of Data Handling

1. **Screen Frames**: When you explicitly initiate screen sharing and accept the Android system `MediaProjection` dialog, ScreenSight AI captures screen frames at 1 FPS, resizes and compresses them to JPEG in memory, and transmits them directly to Google's Gemini Live API over a secure WebSocket connection.
2. **Microphone Audio**: When a live session is active and your microphone is unmuted, 16 kHz PCM audio chunks are streamed directly to Google's Gemini Live API.
3. **User-Owned API Account**: All API calls are authenticated with your own Google AI Studio Gemini API key.
4. **No Developer Servers or Telemetry**: ScreenSight AI operates no backend servers, collects no analytics or telemetry, and has no access to your keys, screen frames, or conversations.
5. **Local Key Persistence**: Your Gemini API key remains encrypted on your Android device and can be deleted at any time from API Settings.
6. **No Disk Recording**: Screen frames and voice buffers are processed in volatile memory (`RAM`) only and are never saved as video or audio files on your device storage.
7. **Immediate User Revocation**: You can mute your microphone, stop screen sharing, or end the live session at any time from inside the app or directly from the Android foreground service notification.
8. **Google Gemini API Policies**: Because screen frames and voice audio are sent directly to Google's Gemini API to generate responses, Google's Gemini API Terms of Service and data retention policies apply to the data processed by your Google AI Studio account.
