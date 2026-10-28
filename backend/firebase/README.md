# Unscroll social layer — Spark plan

This Firebase setup powers **Focus League** on Firebase's no-cost Spark plan: Google accounts, synced profiles and focus history, private friends-only leaderboards, and Monday-reset weekly score history.

The Android app writes through Firestore transactions. The production `firestore.rules` file requires a linked profile/session transaction, allows one active round per person, prevents a completed round until its server request time has reached the stored end time, and only then allows aggregate points to change. This provides strong beta protection without Cloud Functions or a billing account; the future `functions/` folder is retained as an optional Blaze-plan upgrade for fully server-authoritative scoring.

## Firebase console setup

1. Create a Firebase project and add Android app package `com.nextmove.focus`.
2. Download `google-services.json` to `app/google-services.json`. Do not commit it.
3. Enable **Google** authentication. The app uses Android Credential Manager; after sign-in, it automatically creates a League profile in Firestore.
4. Create a Firestore database in **Production mode**.
5. Deploy the security rules and composite index from this folder. Install Firebase CLI, authenticate, then run:

```sh
npm --prefix functions install
firebase use --add
firebase deploy --only firestore:rules,firestore:indexes
```

No Cloud Functions are required for this Spark-plan release. Firestore free quotas and all Firebase billing/usage controls live in the Firebase/Google Cloud project.

## Optional Blaze upgrade

- `bootstrapProfile(username)` — reserves a unique handle.
- `startFocusSession(task, durationMinutes)` — server timestamps a round.
- `completeFocusSession(sessionId)` — awards points only after the server end time.
- `endFocusSession(sessionId)` — marks a round as ended early.
- `createFriendInvite()` / `acceptFriendInvite(inviteCode)` — creates a private connection.
- `getLeague()` — returns the caller plus their accepted friends, ranked by today’s score.

The Android Firebase client wiring requires the project-specific `google-services.json`; it is intentionally not included here.

For Google sign-in, add the release signing certificate SHA-1 to the Android app in Firebase Project settings, enable the Google provider under **Authentication → Sign-in method**, then download the refreshed `google-services.json`. The updated file includes the web OAuth client ID used by the sign-in flow.
