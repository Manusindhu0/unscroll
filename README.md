# Next Move — Android beta

An Android-first focus app built around one small loop:

**Focus → finish → decide the next move → return when ready.**

It deliberately avoids a numeric countdown. A horizontal focus light stays red for the first half of the session, turns yellow in the final stretch, and turns green only when the session is complete.

## What works in this beta

- Task-based 25, 45, or 60 minute focus sessions
- Default-deny app blocking: every launchable third-party app is blocked unless it is on an allowlist
- A fullscreen focus overlay when the user opens a selected distracting app
- Red-to-yellow progress light, with no remaining-time display
- A next-task prompt after successful sessions
- One or two configurable return reminders (the second is the final nudge)
- Edit or skip the next task without guilt mechanics
- Local username, focus score, profile statistics and completed/ended-early session history
- Notification listener to clear ordinary notifications during focus, plus optional Android Do Not Disturb control for sounds and banners

## Run it

1. Open this directory in Android Studio.
2. Let Android Studio install the Android SDK / Gradle components it requests.
3. Run on an Android 8.0+ device or emulator.
4. Inside the app, choose the small set of **allowed apps**, then enable **Next Move focus blocker** in Android Accessibility Settings.

For a real device, allow notifications if you want break reminders. In **Focus shield**, enable Notification access and Do Not Disturb access if you want all ordinary notifications to disappear and stay silent while focusing.

## Intentional safety boundary

This is not a kiosk or device-management app. It blocks only the apps the user explicitly chooses and leaves calls, emergency access, Android settings, and the Next Move app available. That makes the product usable for normal personal phones while still creating meaningful friction against distraction.
