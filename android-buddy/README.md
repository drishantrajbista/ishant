# Buddy (Android)

An Android take on [Clicky](https://github.com/farzaa/clicky), the open-source (MIT) macOS AI buddy by Farza.
Buddy lives as a floating bubble on your phone. Hold it, ask a question about whatever is on screen, and let go.
Buddy looks at your screen, answers out loud, and a blue triangle pointer flies to the thing it is talking about.

Credit: the idea, the casual spoken-style system prompt and the `[POINT:x,y:label]` pointing contract come from
Clicky (MIT, https://github.com/farzaa/clicky). This is an independent Android re-implementation.

## How it works

1. Press and hold the bubble (push-to-talk). Speech is transcribed with Android `SpeechRecognizer`.
2. On release, Buddy hides its own overlays, grabs the latest screen frame, downscales it (long edge <= 1280) and
   sends it as a JPEG with your transcript and the last ~10 turns to the AI provider, streaming the reply.
3. The model ends its reply with `[POINT:x,y:label]` or `[POINT:none]`. The tag is stripped, the text is spoken with
   Android `TextToSpeech`, and the pointer flies along a bezier arc to the (rescaled) coordinate.

## Architecture mapping

| Clicky (macOS) | Buddy (Android) |
|---|---|
| Menu bar app, push-to-talk hotkey | Foreground service + draggable floating bubble (`WindowManager`, hold to talk) |
| AssemblyAI streaming transcription | `SpeechRecognizer` with partial results |
| ScreenCaptureKit screenshots | One `MediaProjection` + `VirtualDisplay` + `ImageReader` kept for the service's lifetime |
| Claude via Cloudflare Worker `/chat` | `GeminiClient` (default) or `ClaudeClient` (direct or via a `/chat` proxy), SSE streaming |
| `CompanionManager` state machine | `BuddyService` (idle / listening / processing / responding) |
| `parsePointingCoordinates` | `parsePointingCoordinates` in `Pointing.kt` (no screen number; single screen) |
| Blue cursor overlay `NSPanel` | Full-screen non-touchable overlay window drawing the triangle (`PointerOverlay`) |
| ElevenLabs TTS | Android `TextToSpeech` + a small reply text bubble |
| Setup in menu bar panel | `MainActivity` (Jetpack Compose) |

## Setup

### Gemini (free, default)

1. Get a free key at https://aistudio.google.com/apikey (no card needed).
2. Open Buddy, keep **Gemini (free)** selected, paste the key.
3. The model defaults to `gemini-flash-latest`. Model ids change; tap **Fetch models** to list the ones your key can
   use with `generateContent` and tap one to select it, or type an id.
4. If you hit the free-tier rate limit, Buddy says "free tier limit hit, try again in a minute".

### Claude (paid, optional)

1. Choose **Claude (paid)** and paste an Anthropic API key (sent as `x-api-key`, `anthropic-version: 2023-06-01`), or
2. Set a **proxy base URL** (for example Clicky's Cloudflare Worker). Requests go to `{proxy}/chat` with no key header.
3. Pick `claude-sonnet-5-5` (default) or `claude-opus-5-5`.

### Permissions and start

Grant **overlay** (draw over other apps), **microphone** and, on Android 13+, **notifications**, then tap
**Start Buddy** and accept the screen-capture prompt. Hold the bubble to talk; drag it to move; tap it while it is
speaking to stop. Stop Buddy from the app or the notification's Stop action.

## Build

Requires JDK 17+ and the Android SDK (platform 35, build-tools 35.0.0).

```
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`. A prebuilt copy is in `release/buddy-debug.apk` for
sideloading.

## Privacy

- Screenshots and transcripts are sent to your chosen provider (Google Gemini, Anthropic, or your proxy) only when you
  release the bubble after push-to-talk. Nothing is captured or sent otherwise. There is no analytics.
- **Gemini free tier:** Google may use your prompts and screenshots to improve its products. Do not use Buddy with the
  free tier on screens that show sensitive information (passwords, banking, private messages).
- API keys are stored with `EncryptedSharedPreferences` on the device.
- Speech recognition and TTS use your phone's installed engines, which may be cloud-based depending on the device.
- Buddy hides its bubble and pointer before each capture, but Android may still show a screen-capture indicator.
