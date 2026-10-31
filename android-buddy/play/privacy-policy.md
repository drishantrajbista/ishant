# Buddy Privacy Policy

_Last updated: <DATE>. Contact: <YOUR_EMAIL>_

Buddy ("the app") is an AI assistant that floats over your screen. This policy explains what the app does with your data. The short version: the developer receives nothing. The app sends data only to the AI service you choose, only when you use it.

> Before submitting to Google Play this policy must be hosted at a public URL (for example a GitHub Pages page) and that URL entered in the Play Console.

## What the app collects and where it goes

**Screenshots and screen content.** When you release the push-to-talk bubble, the app captures your screen (with its own bubble and drawings hidden).
- *Claude app mode:* the screenshot is handed to the Claude app installed on your phone, which is governed by Anthropic's terms and your Claude account. The app itself sends nothing over the network.
- *Claude API mode:* the screenshot, your question, and (if you enabled the accessibility service) a list of on-screen elements and the results of actions are sent to Anthropic's API (api.anthropic.com) using your own API key, to produce an answer or to decide what to do next.
Screenshots are not captured at any other time. The app does not keep a history of them. The most recent screenshot shared with the Claude app stays in the app's private cache folder until it is overwritten by the next share or the system or you clear the app's cache.

**Voice.**
- *Phone voice (default without a Gemini key):* speech is recognised by your phone's Android speech recognizer. Depending on your device, this may be processed by Google or your device maker under their own policies.
- *Gemini voice (optional):* the app records audio while you hold the bubble and sends the recording to Google's Gemini API (generativelanguage.googleapis.com) with your own key, only to turn it into text. On Google's free tier, Google may use submitted content to improve its products. The temporary recording file is deleted right after it is read.
- The transcribed text is used as your question (see above).

**Accessibility service (optional).** If you turn on Buddy's accessibility service, the app reads the visible content of the current screen and performs taps, swipes, typing and navigation, but only to carry out a request you just made by voice. This screen content is used for that request only: it is sent to Anthropic as described above, and it is not stored or logged by the app and is never sent to the developer. The app refuses to type into password fields. You can switch the service off at any time in Android Settings > Accessibility.

**API keys and settings.** Your Anthropic and Gemini API keys and your settings are stored on your device using Android's encrypted storage (EncryptedSharedPreferences). A key is sent only to the provider it belongs to, over HTTPS, to authenticate your requests. Backups are disabled for the app.

**Transcript (on by default, can be switched off).** Buddy saves the last 300 things you said and its replies in a private file on your device so you can read, copy or delete them; you can clear them all or turn "Keep transcript" off. The last few exchanges are also sent to Anthropic as context in your next request (Claude API mode), and a cropped screenshot you choose replaces the full screenshot for one question. Nothing is sent to the developer.

**Text Buddy writes for you.** When you ask Buddy to write something (a note, a caption, a message), it puts that text on your clipboard so you can paste it. It is not sent anywhere by Buddy and nothing is sent on your behalf.

**Screenshots you save.** When you ask Buddy to take a screenshot (by voice, or with Save in the crop view), the picture goes to your device's gallery: either the phone's own screenshot, or a PNG in Pictures/Buddy. It stays on your device; Buddy does not upload it and the developer never receives it.

**Memory (on by default, can be switched off).** To adapt to how you talk, Buddy can save up to 30 short notes about your preferences and up to 50 shortcuts from phrases you use to app names. They are stored only on your device, in the same encrypted storage; you can view and delete each one, clear them all, or turn "Learn from me" off in the app. Buddy refuses to save notes that look like passwords, codes or card numbers. In Claude API mode the notes are included as context in your own requests to Anthropic. They are never sent to the developer.

**Clipboard.** In Claude app mode the app places your question on the clipboard so you can paste it if needed, and reads the clipboard only when you tap the bubble to show Claude's answer.

**Calendar and notifications (optional, off by default).** If you allow it, Buddy can read your upcoming calendar events
(read-only) and, with Notification access, the last 30 notifications (app, title, text, time), which are held in memory
only and never written to disk. They are used only when you ask (for example for a briefing); in Claude API mode that text
is sent to Anthropic to answer you. You can revoke either at any time in Android Settings.

## What the app does not do
- No analytics, no crash-reporting SDK, no advertising SDKs, no trackers.
- No account with the developer, and no data sent to the developer or any server the developer runs.
- No sale of data. No sharing of data except sending your content to the AI provider you chose, to get the answer you asked for.
- The app keeps its conversation with Claude in memory only (it is discarded when Buddy stops, and started fresh after 10 minutes of inactivity).

## Third parties
When you use them, Anthropic (Claude app or API) and Google (Gemini voice, Android speech and text-to-speech) receive your data under their own privacy policies. Please read them: https://www.anthropic.com/legal/privacy and https://policies.google.com/privacy.

## Children
The app is not directed to children under 13 and should not be used by them.

## Your choices and deleting your data
Because the developer holds none of your data, there is nothing to delete on our side. To remove what is stored on your device, clear the app's storage or uninstall it. To delete data held by Anthropic or Google, use their privacy tools.

## Changes
If this policy changes, the new version will be published at the same URL with a new date.

## Contact
<YOUR_EMAIL>
