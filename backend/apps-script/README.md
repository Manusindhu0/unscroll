# Unscroll direct feedback email (free Google Apps Script setup)

This small script lets people submit Help messages in Unscroll without opening
their mail app. The receiving inbox lives in private Script Properties, never
in the Android source or repository.

## One-time owner setup

1. Go to [script.google.com](https://script.google.com/) while signed in to
   the Google account whose inbox should receive feedback, and choose **New project**.
2. Delete the starter code, then paste everything from `Code.gs`.
3. Open **Project Settings → Script properties**. Add a property named
   `UNSCROLL_FEEDBACK_TOKEN` and give it a long random value (at least 32
   characters). Do not share it publicly.
4. Add another Script property named `UNSCROLL_FEEDBACK_RECIPIENT` with the
   owner inbox address that should receive the messages.
5. Choose **Deploy → New deployment → Web app**:
   - Execute as: **Me**
   - Who has access: **Anyone**
6. Authorize Gmail permission, deploy, and copy the URL ending in `/exec`.

Send the `/exec` URL and the token to the project maintainer through this
private build chat. They are needed to connect the Android app. Do not put the
token in a public post, GitHub repository, or screenshot.

## What it sends

- Feedback category
- Message
- Unscroll username
- App and Android version

The script has a one-minute per-username cooldown to reduce accidental repeat
submissions during beta testing.
