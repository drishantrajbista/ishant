# Play Console: Data safety answers

Answers for the Data safety form, based on what the app actually does. Review the wording against the current form before submitting. Data goes only to the AI provider the user picks (Anthropic, and Google if Gemini voice is chosen) to give the user the answer they asked for. The developer receives nothing.

## Overview questions
| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | Yes |
| Is all of the user data collected by your app encrypted in transit? | Yes (HTTPS/TLS to api.anthropic.com and generativelanguage.googleapis.com) |
| Do you provide a way for users to request that their data is deleted? | The developer holds no user data. Users clear app storage or uninstall; provider-held data is deleted through Anthropic/Google. Explain this in the free-text field. |
| Is any data sold? | No |
| Used for advertising or marketing, or for tracking? | No |

## Data types
| Data type (Play category) | Collected | Shared | Why | Optional? |
|---|---|---|---|---|
| Photos and videos > Photos (screenshots of the screen) | Yes, when the user releases the bubble | Yes, with Anthropic (Claude API mode only). In Claude app mode it is handed to the Claude app on the device and the app sends nothing itself. | App functionality | Yes: Claude app mode sends nothing |
| Audio > Voice or sound recordings | Yes, only with Gemini voice | Yes, with Google (Gemini API) | App functionality | Yes: Phone voice is the alternative |
| App info and performance / App activity > Other user-generated content or actions | Yes (the user's spoken question as text; screen elements and action results when the accessibility service is on) | Yes, with Anthropic (Claude API mode) | App functionality | Yes |
| Personal info, financial info, location, contacts, messages, health, web history, device or other IDs | No (the app does not collect them on its own; screenshots could incidentally contain anything on screen, which the user chooses to send) | n/a | n/a | n/a |

| Calendar > Calendar events | Yes, only if the user allows READ_CALENDAR and asks about their schedule | Yes, with Anthropic (Claude API mode) | App functionality | Yes |
| Messages / App activity > notifications text | Yes, only with the optional Notification access, in memory only, when the user asks | Yes, with Anthropic (Claude API mode) | App functionality | Yes |

Notes for the free-text fields:
- Transfers go to AI service providers acting on the user's request, using the user's own API key or account. Nothing is sent to the developer.
- Not processed for any purpose other than producing the response.
- No analytics or advertising SDKs are included.

## Stored on the device only (not "collected")
API keys (EncryptedSharedPreferences), settings, and the short notes and app-phrase shortcuts Buddy learns when "Learn from me" is on (Transcript: the last 300 exchanges, optional, in a private file; Memory: the user can see, delete or switch it off; notes are sent to Anthropic only as context in the user's own Claude API requests). The last screenshot handed to the Claude app is kept in the app's private cache until overwritten.
