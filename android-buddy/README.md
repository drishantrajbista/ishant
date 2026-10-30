# Buddy (Android)

An Android take on [Clicky](https://github.com/farzaa/clicky), the open-source (MIT) macOS AI buddy by Farza.
Buddy lives as a floating bubble on your phone. Hold it, ask a question about whatever is on screen, and let go.
Depending on the mode, Buddy hands your screenshot and question to the Claude app, or answers out loud itself while a
blue triangle pointer flies to the thing it is talking about.

Credit: the idea, the casual spoken-style system prompt and the `[POINT:x,y:label]` pointing contract come from
Clicky (MIT, https://github.com/farzaa/clicky). This is an independent Android re-implementation.

## Modes

| Mode | What happens | You need | Cost |
|---|---|---|---|
| **Claude app (free)**, the default | Buddy takes a sharp screenshot, copies your question, and opens the Claude app with both. You tap send. | The Claude app, signed in | Your normal Claude account. No API key. |
| **Gemini (free, spoken answers)** | Buddy sends the screenshot and your question to Gemini, speaks the reply sentence by sentence and points at things. | A free Gemini key (no card) | Free tier |
| **Claude API (pay per use)** | Same as Gemini mode, with Claude (Haiku 5.5 by default, Sonnet 5.5 or Opus 5.5 optional). | An Anthropic API key with prepaid credit | Pay per use |

### How the Claude app hand-off works

1. You hold the bubble and speak; the question is transcribed (see *Voice recognition*).
2. Buddy captures the screen (long edge up to 2400 px, JPEG quality 90) and writes it to `cache/shots/screen.jpg`.
3. The file is exposed through a `FileProvider` (`<applicationId>.files`) as a `content://` URI.
4. The question is copied to the clipboard (label "Buddy question") as a fallback, because we can't know whether the
   Claude app reads shared text together with a shared image.
5. Buddy fires `ACTION_SEND` (`image/jpeg`, `EXTRA_STREAM` + `EXTRA_TEXT`, read-permission grant) addressed to
   `com.anthropic.claude`. The Claude app opens with the screenshot attached. If your question didn't arrive with it,
   paste it.
6. A quick tap on the idle bubble shares just the screenshot (nothing is copied).

Buddy starts the Claude app from the background, which Android only allows an app with the overlay permission while one
of its overlay windows is visible (Android 15 tightened this). That is why the bubble is put back on screen before
the app is launched. If the Claude app isn't installed, Buddy opens its Play Store page.

### Which mode is fastest, and why

- **Claude app** has the shortest path to a question in front of Claude: there is no API call from Buddy at all. The
  moment your speech is recognized the Claude app opens with the screenshot attached, and the answer streams in
  Claude's own UI as soon as you tap send. It is the cheapest too (nothing beyond your Claude account), but you read
  the answer instead of hearing it, and there's no pointer.
- **Gemini** is the fastest for hands-free spoken answers: thinking is switched off, the reply is streamed, and each
  sentence is spoken as soon as it is complete rather than after the whole reply.
- **Claude API with Haiku 5.5** is the fastest and cheapest Claude option for spoken answers. Thinking is kept short
  (`effort: low`); Sonnet 5.5 and Opus 5.5 are slower but stronger.

What Buddy does to stay quick in the API modes: the screenshot is taken while speech recognition is still finishing,
the connection to the provider is warmed up when you start holding the bubble, pixel work (frame copy, scaling, JPEG
encoding) happens off the main thread, and the reply is spoken while it is still streaming.

## Voice recognition

Setup has a **Voice recognition** setting with two engines.

- **Gemini (most accurate)** is the default whenever a Gemini key is saved. Buddy records your voice itself
  (`MediaRecorder`, AAC, 16 kHz mono, about 32 kbps, so the upload stays small) and Gemini listens to the audio. It
  copes with accents and with several languages in one sentence, each word in its own script.
  - Recording starts the instant your finger touches the bubble (so the first word isn't clipped), keeps going about
    300 ms after you let go (so the last word isn't clipped), and is capped at 60 seconds. If the touch turns out to be
    a tap or a drag, the recording is thrown away. The bubble buzzes when the hold is recognized.
  - In **Gemini mode** the audio and the screenshot go in one request. Gemini starts its reply with a
    `[HEARD: ...]` tag; Buddy strips it, never speaks it, and shows what was heard above the reply.
  - In the **Claude modes** Buddy first sends the audio to Gemini for a transcript (one quick non-streaming call), while
    the screenshot is being taken, then continues as usual with that text.
  - Needs a (free) Gemini key. Your recording is sent to Google; on the free tier Google may use it to improve its
    products. If the free limit is hit Buddy says so; switch the voice engine to Phone to carry on.
- **Phone (Google speech)** is the default when there is no Gemini key. It uses Android's `SpeechRecognizer`.
  - Pick a **Speech language** (device default, English US/UK/India, Nepali, Hindi).
  - If you pause mid-sentence while still holding, Buddy restarts the recognizer and keeps what you said, so it never
    cuts you off; everything you said is joined when you let go.
  - The bubble buzzes when the recognizer is ready for you to start.

Spoken replies containing Devanagari are read with a Nepali voice (Hindi if Nepali isn't installed).

## Architecture mapping

| Clicky (macOS) | Buddy (Android) |
|---|---|
| Menu bar app, push-to-talk hotkey | Foreground service + draggable floating bubble (`WindowManager`, hold to talk) |
| AssemblyAI streaming transcription | Gemini audio understanding, or `SpeechRecognizer` |
| ScreenCaptureKit screenshots | One `MediaProjection` + `VirtualDisplay` + `ImageReader` kept for the service's lifetime |
| Claude via Cloudflare Worker `/chat` | `GeminiClient`, `ClaudeClient` (direct or via a `/chat` proxy), or a hand-off to the Claude app |
| `CompanionManager` state machine | `BuddyService` (idle / listening / processing / responding) |
| `parsePointingCoordinates` | `parsePointingCoordinates` in `Pointing.kt` (no screen number; single screen) |
| Blue cursor overlay `NSPanel` | Full-screen non-touchable overlay window drawing the triangle (`PointerOverlay`) |
| ElevenLabs TTS | Android `TextToSpeech`, streamed sentence by sentence, plus a small reply text bubble |
| Setup in menu bar panel | `MainActivity` (Jetpack Compose) |

## Setup

1. Install the app (`release/buddy-debug.apk`, or build it below). It is signed with the standard Android debug key, so
   a newer build installs over an older one.
2. Open Buddy and pick a mode.
   - **Claude app:** install the Claude app and sign in. That's it.
   - **Gemini:** get a free key at https://aistudio.google.com/apikey (no card) and paste it. The model defaults to
     `gemini-flash-latest`; model ids change, so tap **Fetch models** to list the ones your key can use, or type one.
   - **Claude API:** paste an Anthropic API key from console.anthropic.com (prepaid credit required), or set a **proxy
     base URL** (for example Clicky's Cloudflare Worker): requests then go to `{proxy}/chat` with no key header.
     Pick Haiku 5.5 (default), Sonnet 5.5 or Opus 5.5.
3. Pick a voice recognition engine (see above).
4. Grant **overlay** (draw over other apps), **microphone** and, on Android 13+, **notifications**.
5. Tap **Start Buddy** and accept the screen-capture prompt. Hold the bubble to talk; drag it to move; tap it while it
   is working or speaking to stop. Stop Buddy from the app or the notification's Stop action.

## Build

Requires JDK 17+ and the Android SDK (platform 35, build-tools 35.0.0).

```
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew testDebugUnitTest assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`. A prebuilt copy is in `release/buddy-debug.apk` for
sideloading.

## Privacy

- Screenshots are only captured when you release the bubble after push-to-talk. There is no analytics.
- **Claude app mode:** Buddy sends nothing over the network itself. The screenshot goes to the Claude app on your phone,
  under your Claude account's own terms. With Gemini voice on, the recording is still sent to Google for
  transcription.
- **Gemini mode:** the screenshot (and, with Gemini voice, your recording) is sent to Google. On Gemini's free tier
  Google may use prompts, screenshots and audio to improve its products, so don't use it on screens showing sensitive
  information.
- **Claude API mode:** the screenshot and your words go to Anthropic (or to your proxy).
- API keys are stored with `EncryptedSharedPreferences` on the device.
- The phone's speech recognizer and text-to-speech engines may be cloud-based depending on the device.
- Buddy hides its bubble and pointer before each capture, but Android may still show a screen-capture indicator.
