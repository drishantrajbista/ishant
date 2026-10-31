# Release checklist: publishing Buddy on Google Play

Files in this folder: `store-listing.md`, `privacy-policy.md`, `data-safety.md`, `accessibility-declaration.md`. The signed bundle is `../release/buddy-release.aab` (applicationId `dev.buddy`, versionCode 10, versionName 1.5).

## 1. Before you start
- [ ] **Keep the upload keystore safe.** `buddy-release.jks` plus its password signed the bundle; it is the Play *upload key*. Back it up somewhere private. Never commit it. Play App Signing will re-sign the app with Google's own key for distribution, so the upload key only has to stay consistent across your uploads (if you lose it, Play support can reset the upload key, but it's a hassle).
- [ ] Host `privacy-policy.md` at a **public URL** (for example GitHub Pages) after replacing `<YOUR_EMAIL>` and `<DATE>`.
- [ ] Prepare graphics (none exist in the repo yet): a **512x512 PNG icon**, a **1024x500 feature graphic**, and **at least 2 phone screenshots**. The app currently only has an adaptive vector launcher icon, so the 512x512 icon must be supplied.
- [ ] Record a short **demo video** of the accessibility feature (see `accessibility-declaration.md`). Google often asks for one for accessibility apps.

## 2. Play Console
1. Create a developer account at play.google.com/console (one-time US$25). Personal accounts created after Nov 2023 must run a **closed test with 12+ testers for 14 days** before production access (check Google's current rules); organisation accounts are exempt.
2. **Create app:** name Buddy, default language, App, Free, accept the declarations.
3. **Set up your app** (Dashboard checklist): privacy policy URL, ads (No), app access (explain that a Claude account or API key is needed to try all features and give reviewers a way to test: a test API key or instructions), content rating questionnaire, target audience (18+ is simplest; not for children), data safety (use `data-safety.md`), government apps (No), financial features (No), health (No).
4. **App content > Accessibility / sensitive permissions:** complete the AccessibilityService declaration with `accessibility-declaration.md` and attach the demo video. Also declare the foreground service types (mediaProjection, microphone) with the text there.
5. **Store listing:** paste from `store-listing.md`, upload the icon, feature graphic and screenshots.
6. **Release > Testing > Internal or Closed testing** first, then **Production**: create a release, upload `buddy-release.aab`, add release notes, roll out. Accept Play App Signing when prompted.

## 3. Likely review friction
- **AccessibilityService** is the main one. Google only allows it for apps whose core functionality needs it and requires a clear in-app disclosure and a consent step. Buddy's setup screen explains the feature and the user turns the service on themselves in Android Settings; keep that, and make sure the store listing says the app uses accessibility (it does). Expect questions about why it isn't just an automation tool.
- **Screen capture (MediaProjection) and "draw over other apps"** are scrutinised too. The privacy policy and declarations explain that capture happens only on push-to-talk and goes only to the user's chosen AI provider.
- **Autonomous actions:** reviewers like the safeguards (asks aloud before sending/paying/deleting, never types passwords, tap to stop). Mention them.
- **Third-party AI:** the app sends screen content to Anthropic/Google; the data safety form and privacy policy must say so. Don't claim "no data leaves the device".
- **Trademarks:** the app is not affiliated with Anthropic or Google (the listing says so). Avoid their logos.
- If rejected, the message names the policy; fix the specific point and resubmit.

## 4. Updating later
Increase `versionCode` for every upload (next is 20), rebuild with `./gradlew bundleRelease` using the same keystore, upload the new `.aab`.
