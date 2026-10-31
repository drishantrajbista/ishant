# Hyperframes Composition Brief: Buddy (Android)

## Objective
Create a short launch-style brag video for Buddy, an Android take on Clicky (the macOS AI buddy by Farza).

## Output
- Composition directory: `brag-output-2026-10-31-132010/composition/`
- Rendered video: `brag-output-2026-10-31-132010/brag.mp4`
- Format: landscape, 1920x1080, 30 fps
- Duration: ~20.5 seconds (must stay within 15–25)

## Source Material
- Project root: `/home/user/ishant/android-buddy`
- Primary files read: `README.md`, `app/src/main/java/dev/buddy/Pointing.kt` (agent prompt), `BubbleOverlay.kt` / `DrawingOverlay.kt` (bubble + cursor look), `play/store-listing.md`, `play/icon-512.png`
- Product name: Buddy
- Tagline / strongest claim: Hold the bubble, ask out loud, and a cursor flies across your phone and points at the answer.
- Key UI to recreate (the Android app UI can't be rendered in a browser, so rebuild it faithfully from the source specs):
  - The floating bubble: frosted translucent white circle (~74px), soft shadow, thin border, coral (#D97757) cursor glyph with a small speech dot. States: waveform ring while listening, rotating coral arc while thinking.
  - The cursor: coral (#D97757) pointer with a white halo, flies along a curved path; draws a coral circle (5–6px stroke over a ~12px white halo) around the target.
  - Reply card: rounded white card, soft shadow, lowercase spoken-style text.
  - Status pill: coral "Doing: turning on dark mode".
  - A generic Android Settings list (Connections, Display, Battery, Sounds and vibration, Notifications, Accessibility) and a Display page with a "Dark mode" toggle; then a dark YouTube-style screen. No third-party logos; "YouTube" only as a text label.
  - Real app icon: `assets/img/buddy-icon.png` (from `play/icon-512.png`).
- Copy that must appear verbatim (all grounded in the README/app):
  - "how do i turn on dark mode?" (voice transcript)
  - "open display, dark mode is right at the top." (reply card, Clicky-style lowercase)
  - "done. dark mode is on."
  - "YouTube khola" (Nepali instant command from README) and "opening YouTube"
  - Example commands: "set an alarm for 7am", "timer for 5 minutes", "what's my day?", "pause the music", "what did I miss?", "torch on gara"
  - Outro: "Buddy", "An Android take on Clicky.", "Hold the bubble. Ask anything.", small credit "Inspired by Clicky (MIT) · answers by Claude"

## Creative Direction
- Tone preset: default (punchy, playful, clean, postable)
- Creative direction: "Clicky, but it lives on your phone."
- Interpretation: 5–6 scenes, soft staggered transitions (old content out, then new in; never a muddy double exposure), the phone does real-feeling interactions (press, cursor flight, taps, toggle flip, page slides). Calm confidence, no hype words.
- Angle: Show Buddy in use on one Settings task: it points, then does it for you; then Nepali instant commands; then the breadth of real commands; end on the icon.
- Hook (0–3.2s): finger holds the bubble, waveform ring pulses, transcript types "how do i turn on dark mode?". Headline: "Hold the bubble." then "Ask out loud."
- Outro / punchline: icon lands on the 17.02s strong cue; "Hold the bubble. Ask anything."
- Avoid: generic SaaS language, abstract filler visuals, invented numbers or claims, waveform/equalizer graphics, strobing.

## Visual Identity
- Background: #F2F2F7 (the app's grouped background), with a soft warm coral radial glow (#FBE9E2) top-right
- Text: #14181D, secondary #5B6470
- Accent: #D97757 (brand coral)
- Display font: Bricolage Grotesque 700/800 (ship the font file in assets/fonts with @font-face; fall back to a bundled font if it can't be fetched)
- Body font: Figtree 400–700 (same rule)
- Phone mockup: dark bezel (#1A1C20), 64px outer radius, screen ~452x892, light theme #F4F4F8 rows on white grouped cards; it inverts to dark mode in scene 3.

## Storyboard
Use `brag-output-2026-10-31-132010/brag-plan.md` as the creative contract.
1. Hook — 3.2s — bubble pressed, listening ring, transcript typing; "Hold the bubble." / "Ask out loud."
2. Reveal — 3.8s — thinking arc, cursor flies bubble → "Display" and circles it; reply card; "Buddy sees your screen and points." + "Like Clicky on the Mac, now on your Android."
3. Highlight: does it for you — 4.0s — cursor taps Display, page slides, status pill, cursor taps the toggle, phone turns dark, card "done. dark mode is on."; "Or it does it for you." + "Claude taps, types and scrolls."
4. Highlight: Nepali — 3.2s — transcript "YouTube khola", YouTube screen slides up at once, card "opening YouTube"; "Speaks Nepali. Answers in English." + "Simple commands run instantly, no wait for Claude."
5. Highlight: breadth — 3.0s — the six example commands pop in as chips; "Alarms, timers, calendar, music." + "Free with your Claude app, or use the Claude API."
6. Outro — 3.4s — icon, "Buddy", "An Android take on Clicky.", "Hold the bubble. Ask anything.", credit line.
Reading time: every line holds settled ≥0.3s per word.

## Audio
- Audio role: warm bed with light interface accents
- Audio arc: music from the start; light SFX on press/cursor/taps/toggle/chips; music fades under the final logo hold
- Music: `assets/music/happy-beats-business-moves-vol-1-by-ende-dot-app.mp3`
- Music treatment: steady level under visuals, fade out over the last ~1s
- Music cue guidance: preset `/home/user/ishant/.claude/skills/brag/assets/music/cues/happy-beats-business-moves-vol-1-by-ende-dot-app.music-cues.json` — strong cues 17.02s, 17.52s; beat grid from 3.02s every ~0.5s. Lock the outro icon to 17.02 (±0.15). Optional beat snaps: cursor arrival ~4.53, toggle tap ~8.52, chips on every other beat from ~14.52.
- Audio-reactive treatment: subtle — e.g. the coral background glow warmth or the bubble's soft shadow breathing with RMS. No equalizer visuals.
- Audio-coupled moments: bubble press (soft tap), cursor flights (soft whoosh), cursor arrivals/taps (UI clicks), toggle flip (short positive cue), chips (light card/ui ticks, quiet), outro icon (one announcement cue).
- SFX selection guidance: subtle interface/ui sounds; low high-frequency risk for repeated ones.
- SFX analysis guidance: `/home/user/ishant/.claude/skills/brag/assets/sfx/sfx-analysis.md` (+ .json); SFX files in `/home/user/ishant/.claude/skills/brag/assets/sfx/`.
- Exact SFX choice: Hyperframes chooses filenames, timestamps, density and volume after the animation exists.
- Audio files: copy chosen music/SFX into `composition/assets/`.

## Hyperframes Instructions
Use the installed HyperFrames skills (`hyperframes` core: contract, format, motion, sound, review; `hyperframes-cli`) as the implementation authority. This is the /brag workflow: no intake interview, no three-storyline step, no generic launch workflow; the storyboard above is final.
Requirements: real-looking product UI and the real icon; all text readable; 15–25s; music + SFX; cues are hints; 1–3 strong cue locks; grounded copy only; run `npx hyperframes check` (zero errors) before render; keep everything local.
