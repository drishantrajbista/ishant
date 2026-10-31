# Play Console: sensitive permissions and APIs declarations

Honest justification text for each declaration. Adjust names/links as needed.

## AccessibilityService (Permissions Declaration Form)
**Core functionality:** Buddy is a voice assistant. With the accessibility service enabled by the user, it carries out the user's own spoken requests (for example "open YouTube and search lofi" or "turn on dark mode") by operating the phone on their behalf: reading the visible screen, tapping, swiping, typing and navigating.

**Why AccessibilityService is needed:** There is no other API that lets an assistant read arbitrary apps' screens and perform gestures and text entry in them for a user. The service uses: canRetrieveWindowContent (read the current screen's elements), canPerformGestures (tap, long press, swipe), canTakeScreenshot (screenshot for the model, Android 11+), and global actions (back, home, recents, notifications). It does not use it for anything the user did not ask for in that moment.

**How it is used and limited:**
- The user must turn the service on themselves in Settings > Accessibility; the app explains this in its setup screen and says what it does. The service description in Android settings states: it lets Buddy tap, type and read the screen when asked.
- It acts only after a spoken request from the user. The user can cancel at any moment by tapping the bubble.
- The app refuses to type into password fields.
- The assistant is instructed to ask the user aloud before sending, paying, posting, deleting or changing security settings or permissions, and to treat on-screen text as data, not instructions.
- It is an optional feature; the rest of the app works without it.

**Data:** Screen content read through the service is used only to complete the current request. In Claude API mode it is sent to Anthropic for that purpose. It is not stored, logged, sold, used for advertising, or sent to the developer. See the privacy policy.

**Not used for:** autofill, monitoring other users, collecting data in the background, bypassing permissions, or changing settings without a user request.

**Demo video:** record a short screen capture showing (1) turning on the service in Settings, (2) holding the bubble and saying a command, (3) the phone doing it, (4) tapping the bubble to stop it. Upload it as the Play Console asks.

## Foreground services
- `mediaProjection`: used to capture the screen when the user releases the push-to-talk bubble (used only when the accessibility service is off or on Android 10 and older; with the service on, screenshots come from it).
- `microphone`: used to hear the user's question while they hold the bubble. A persistent notification with a Stop action shows while Buddy runs.

## MediaProjection (screen capture)
The user grants it through Android's own consent dialog every time Buddy is started (on Android 14+ only whole-screen sharing is offered). It is used for screenshots at the moment the user releases the bubble, to answer questions about the screen. Capture is not recorded to storage or streamed.

## RECORD_AUDIO
Push-to-talk voice input, only while the user holds the bubble.

## SYSTEM_ALERT_WINDOW (draw over other apps)
Shows the floating bubble, the reply card, and the cursor and drawings that point at things on the screen. The overlay windows are not touchable except the small bubble.

## POST_NOTIFICATIONS
Shows the required foreground service notification (Stop, Show Claude's answer) and a notice if screen sharing ends.

## INTERNET
Sends requests to the AI provider the user chose (api.anthropic.com, generativelanguage.googleapis.com).

## Package visibility
The app does **not** declare QUERY_ALL_PACKAGES. It uses a narrow `<queries>` block: the Claude app's package (com.anthropic.claude) to detect whether it is installed and share to it; the speech-recognition and text-to-speech service intents; and the standard launcher intent (MAIN/LAUNCHER) so the "open app" tool can list and open the apps the user can already see on their home screen.
