# Security Policy

## BYOK Client-Side Architecture & Key Storage

ScreenSight AI is a client-side Bring-Your-Own-Key (BYOK) Android application.

- **Android Keystore Encryption**: User-supplied Gemini API keys are encrypted locally using an AES-256-GCM secret key generated and held inside the hardware-backed `AndroidKeyStore`.
- **No Plaintext Logging**: The application never logs full API keys to Logcat, crash reports, or debug views.
- **No Developer Backend**: API keys are transmitted exclusively over TLS/WSS (`wss://generativelanguage.googleapis.com` and `https://generativelanguage.googleapis.com`) directly to Google's Gemini API.

## Important Threat Model Notice

> Your Gemini API key is stored on this device and is used to connect directly to Google's Gemini API. Because this is a client-side application, a determined attacker with physical or root access to the device or application runtime may be able to extract it. Only use an API key you control, and revoke keys in Google AI Studio if your device is lost or compromised.

## Reporting a Vulnerability

If you discover a security vulnerability in ScreenSight AI:
1. Do **not** post exploitable details in a public GitHub issue.
2. Open a private security advisory in the repository or contact the maintainers directly with steps to reproduce.
