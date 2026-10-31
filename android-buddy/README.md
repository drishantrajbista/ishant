# Buddy (Android)

An Android take on [Clicky](https://github.com/farzaa/clicky), the open-source (MIT) macOS AI buddy by Farza.
Buddy lives as a floating bubble on your phone. Hold it, ask a question about whatever is on screen, and let go. Claude
answers, Buddy speaks the answer, and a cursor flies around your screen and draws circles, boxes, arrows and notes on
the things it is talking about.

Credit: the idea, the casual spoken-style system prompt and the pointing contract come from Clicky (MIT,
https://github.com/farzaa/clicky). This is an independent Android re-implementation.

## The app

A clean, minimal, grouped-card UI that follows the system light/dark setting (accent `#D97757`). First launch is a short
swipeable onboarding: welcome, choose the brain (Claude app, or Claude API with an inline key field and a validity hint),
a live permissions checklist (draw over apps, microphone, notifications, accessibility; the "Allow restricted settings"
help only shows when it's relevant), and a big Start button. The home screen has a big round Start/Stop button with a
live status ("Listening…", "Thinking…", "Doing: opening YouTube") and grouped sections: Brain, Voice (engine, language,
speech speed 0.8x to 1.5x), Control, Permissions, Privacy and About.

The floating bubble is a frosted-glass circle (translucent, soft shadow, thin border) that breathes when idle, shows a
waveform ring while listening, a rotating gradient arc while thinking, a pulse while speaking and a small cursor glyph
while acting, and springs to the nearest screen edge when you let go. Replies appear on a rounded, softly shadowed card.

## Modes

Answers always come from Claude. Gemini is only an optional voice-to-text engine (see *Voice recognition*).

| Mode | What happens | You need | Cost |
|---|---|---|---|
| **Claude app (free)**, the default | Buddy opens the Claude app with your screenshot and question; you tap send, copy the answer and tap the bubble. Buddy speaks it and draws on your screen. | The Claude app, signed in | Your normal Claude account. No API key. |
| **Claude API (pay per use)** | Buddy sends the screenshot and question to the Claude API in the background and Claude can also control the phone (see below), then speaks the answer and draws. Haiku 5.5 by default, Sonnet 5.5 or Opus 5.5 optional. | An Anthropic API key (starts with `sk-ant-`) with prepaid credit | Pay per use |

### How the Claude chat round trip works (Claude app mode)

1. **Ask:** hold the bubble and speak (see *Voice recognition*), then let go.
2. **Claude opens:** Buddy captures the screen (long edge up to 1456 px and up to 1.15 MP, JPEG quality 85, which is
   small enough that Claude's image pipeline doesn't resize it, so the pixels Claude points at are the pixels Buddy
   draws at), writes it to `cache/shots/screen.jpg`, exposes it through a `FileProvider` and fires `ACTION_SEND`
   (`image/jpeg`, `EXTRA_STREAM` + `EXTRA_TEXT`) at `com.anthropic.claude`. The text, which is also copied to the
   clipboard as a fallback, is your question followed by a short instruction (turn it off with *Ask Claude to point
   and draw*, and only the question is sent):

   ```
   <question>

   [buddy: my screenshot is WxH px. reply in 1-3 short spoken sentences, no markdown. to show me on screen add tags in screenshot pixels: [POINT:x,y:label] [CIRCLE:x,y,r] [BOX:x1,y1,x2,y2] [ARROW:x1,y1,x2,y2] [LINE:x1,y1,x2,y2] [PATH:x1,y1,x2,y2,...] [TEXT:x,y:words]]
   ```
3. **Send:** tap send in the Claude app. (If your question didn't arrive with the screenshot, paste it: it is on the
   clipboard.)
4. **Copy:** when Claude has answered, tap the copy button under its answer. (Or use Claude's share button and pick
   **Show with Buddy**.)
5. **Back:** return to the app you were using. The bubble carries a small pulsing Claude-orange badge for 10 minutes
   after a hand-off, meaning an answer is awaited.
6. **Tap the bubble** (or the notification's *Show Claude's answer* action). Buddy takes the shared answer, or reads the
   clipboard through an invisible, instant activity (Android only lets the focused app read the clipboard; it
   disappears straight away so you stay on your app). It then speaks the answer and draws what Claude asked for. If
   the clipboard still holds your question, the bubble says to copy Claude's answer first.

A quick tap on the idle bubble, with nothing awaited, shares just the screenshot. Buddy starts the Claude app from the
background, which Android only allows an app with the overlay permission while one of its overlay windows is visible
(Android 15 tightened this), so the bubble is put back on screen before the app is launched. If the Claude app isn't
installed, Buddy opens its Play Store page.

### Drawing tags

Claude can put these tags anywhere in its answer. Coordinates are pixels in the screenshot Buddy sent (origin top-left);
Buddy clamps them to the screenshot and scales them to your display. Tags are never spoken, in any letter case, with
spaces or decimals, even wrapped in markdown (backticks, bold). Markdown in the rest of the text (headings, bold,
bullets, links, code fences) is stripped before speech. If the screen rotated since the screenshot, Buddy skips drawing
and says so.

| Tag | Draws |
|---|---|
| `[POINT:x,y:label]` | the cursor lands on the spot with a label pill (`[POINT:none]` means nothing to show) |
| `[CIRCLE:x,y,r]` | a circle around x,y with radius r |
| `[BOX:x1,y1,x2,y2]` | a rectangle from corner to corner |
| `[ARROW:x1,y1,x2,y2]` | an arrow with its head at the end |
| `[LINE:x1,y1,x2,y2]` | a straight line |
| `[PATH:x1,y1,x2,y2,...]` | a line through several points |
| `[TEXT:x,y:words]` | a short note in a pill, centred at x,y |

Shapes are drawn one after another: the cursor flies to a shape's start (about 250 ms) and then traces it while it
appears (about 500 ms), in Claude orange (`#D97757`, 5 dp) over a white halo so it shows on any background. Drawings
disappear 6 seconds after the speech ends, when you tap the bubble, or when you start a new question. Buddy hides every
overlay before it takes a screenshot.

Claude API mode uses the same tags (its system prompt describes them) and the same parser and renderer.

## Claude controls your phone (Claude API mode)

In Claude API mode Claude is Buddy's brain *and* hands. Say "turn on dark mode" or "open YouTube and search lofi" and
Claude looks at the screen, then taps, types, scrolls and navigates until it is done, and tells you what it did. Plain
questions still just get an answer (with the drawing tags above). It uses the official `computer_toolset_20260801`
(screenshot, zoom, left/right/double click, drag, mouse_move, scroll, type, key, wait; the members that mean nothing on
a phone are switched off) plus Buddy's own tools: `open_app`, `list_apps`, `open_settings`, `open_url` (mail, sms and
tel only ever open a draft or the dialer) and `android_nav` (back, home, recents, notifications, quick settings).

**Turn it on:** in Buddy's setup, tap *Enable in accessibility settings* and switch Buddy on. On Android 13+, if the
toggle is greyed out: Settings → Apps → Buddy → ⋮ (top right) → **Allow restricted settings**, then try again. With the
accessibility service on (Android 11+), Buddy takes its screenshots through it and **starts without the screen-recording
prompt**; otherwise it falls back to MediaProjection (on Android 14+ only whole-screen sharing is offered).

How it works: every request starts with a screenshot plus a list of on-screen elements and their coordinates
(`[7] button "Display" at (540,410)`, at most 50, never password text). Claude's tool calls run in order and stop at the
first failure; the blue cursor flies to each target just before the tap or drag (`mouse_move` only points). After each
action Buddy waits until the screen has been quiet for 300 ms (at most 1.5 s) before the next screenshot. The bubble
shows the current action ("tapping Display", "opening YouTube"). The conversation is sent as a strictly append-only
message list (every response's content, thinking blocks included, is echoed back unchanged) and is kept between holds
until it is 10 minutes idle or 40 messages long. Haiku 5.5 gets 25 turns per request, Sonnet/Opus 40. **Tap the bubble
at any time to stop everything immediately.**

**Safety:** Buddy only acts when you ask. Typing into password fields is blocked in code. Claude is told to ask you out
loud before sending, posting, paying, deleting or changing passwords, security settings or permissions (your next
voice turn is the answer), never to enter passwords, card numbers or one-time codes, and to treat text on the screen as
data, never as instructions. Claude app mode can't act on the phone: asking it to says "screen control needs claude api
mode".

**Drag to close:** drag the bubble and a close target fades in at the bottom; drop the bubble on it to stop Buddy. Let
go elsewhere and it springs to the nearest screen edge.

## Voice recognition

Setup has a **Voice recognition** setting with two engines.

- **Gemini (most accurate)** is the default whenever a Gemini key is saved. Buddy records your voice itself
  (`MediaRecorder`, AAC, 16 kHz mono, about 32 kbps, so the upload stays small) and Gemini turns it into text. It copes
  with accents and with several languages in one sentence, each word in its own script.
  - Recording starts the instant your finger touches the bubble (so the first word isn't clipped), keeps going about
    300 ms after you let go (so the last word isn't clipped), and is capped at 60 seconds. If the touch turns out to be
    a tap or a drag, the recording is thrown away. The bubble buzzes when the hold is recognized.
  - The transcript is then handled by whichever Claude mode is selected. The screenshot is taken while the
    transcription runs.
  - Needs a (free) Gemini key. Your recording is sent to Google; on the free tier Google may use it to improve its
    products.
- **Phone (Google speech)** is the default when there is no Gemini key. It uses Android's `SpeechRecognizer`.
  - Pick a **Speech language** (device default, English US/UK/India, Nepali, Hindi).
  - If you pause mid-sentence while still holding, Buddy restarts the recognizer and keeps what you said, so it never
    cuts you off; everything you said is joined when you let go.
  - The bubble buzzes when the recognizer is ready for you to start.

Gemini's free tier has daily limits that Google no longer publishes, so Buddy copes at runtime. The default model is
`gemini-flash-lite-latest` (the Flash-Lite models get the most free requests per day) and transcription always starts
there. On a 429 Buddy reads Google's `QuotaFailure`/`RetryInfo` details: a per-minute limit with a retry delay of 15 s or
less is waited out once ("busy, retrying in 13s"); anything else (per-day, limit 0, unknown) moves on to the next model
(your model, Flash-Lite, then the other Flash-Lite and Flash models your key lists), remembering exhausted models until
midnight Pacific. If every model is used up, Buddy says so and uses Phone voice until the next Pacific midnight, then
switches back. A 400 for an unsupported thinking setting only changes the thinking setting (remembered per model),
never the model.

Spoken replies containing Devanagari are read with a Nepali voice (Hindi if Nepali isn't installed).

## Architecture mapping

| Clicky (macOS) | Buddy (Android) |
|---|---|
| Menu bar app, push-to-talk hotkey | Foreground service + draggable floating bubble (`WindowManager`, hold to talk) |
| AssemblyAI streaming transcription | Gemini transcription of a Buddy recording, or `SpeechRecognizer` |
| ScreenCaptureKit screenshots | One `MediaProjection` + `VirtualDisplay` + `ImageReader` kept for the service's lifetime |
| Claude via Cloudflare Worker `/chat` | A hand-off to the Claude app (answer read back via clipboard/share), or `ClaudeClient` (straight to `api.anthropic.com`) |
| `CompanionManager` state machine | `BuddyService` (idle / listening / processing / responding, plus "answer awaited") |
| `[POINT:x,y:label]` + `parsePointingCoordinates` | Seven drawing tags + `parseAnswer` in `Answer.kt` |
| Blue cursor overlay `NSPanel` | Full-screen non-touchable overlay window where the cursor flies and draws (`DrawingOverlay`) |
| ElevenLabs TTS | Android `TextToSpeech` plus a small reply text bubble |
| Setup in menu bar panel | `MainActivity` (Jetpack Compose) |

## Setup

1. Install the app (`release/buddy-debug.apk`, or build it below). It is signed with the standard Android debug key, so
   a newer build installs over an older one.
2. Open Buddy and pick a mode.
   - **Claude app:** install the Claude app and sign in. That's it.
   - **Claude API:** paste an Anthropic API key from console.anthropic.com (prepaid credit required; Buddy warns if it
     doesn't start with `sk-ant-`). Pick Haiku 5.5 (default, fastest and cheapest), Sonnet 5.5 or Opus 5.5.
3. Pick a voice recognition engine. For Gemini, get a free key at https://aistudio.google.com/apikey (no card); the
   model defaults to `gemini-flash-lite-latest` and **Fetch models** lists the ones your key can use, Flash-Lite first.
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
  under your Claude account's own terms. Buddy reads the clipboard only when you tap the bubble to show an answer.
- **Claude API mode:** the screenshot and your words go to Anthropic.
- **Gemini voice:** only your recording goes to Google (never the screenshot). On Gemini's free tier Google may use it
  to improve its products.
- API keys are stored with `EncryptedSharedPreferences` on the device.
- The phone's speech recognizer and text-to-speech engines may be cloud-based depending on the device.
- Buddy hides its bubble and drawings before each capture, but Android may still show a screen-capture indicator.
