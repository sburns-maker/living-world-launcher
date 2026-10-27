# Messages integration — second Android milestone

## Implemented
- Real SMS/RCS continues to run through Google Messages as the Android default.
- A previously enabled NotificationListenerService reads only active Google Messages notifications into volatile memory; dismissed notifications are removed.
- **Open chat** uses that notification's original Android PendingIntent, where available.
- **Reply** appears only if Google Messages supplied RemoteInput on the notification. The user must type and explicitly press Send; the PendingIntent is handed directly to Google Messages. Some RCS/group/attachment notifications do not expose usable actions.
- Fictional conversation shortcuts open the existing authenticated Living World inbox within a native Android WebView. This uses the current game chat engine rather than duplicating conversation data on the device. Login may be necessary.

## Not yet complete
- Fully native message threads driven by a documented authenticated server API.
- A single complete real + fictional inbox (Google Messages does not expose its RCS history).
- Reliable rich-media previews/replies for real RCS.
- Character-specific native deep links: fictional shortcut rows currently open the in-app world inbox, not a particular character.
- End-to-end Samsung device tests for notification PendingIntents and in-app session/auth.
- Persistent secure update signing.

## Privacy and storage
- No SMS/MMS provider permission or default SMS-role request.
- No history sync and no real message content sent to the game's AI backend.
- Notification content and action tokens are short-lived process memory only.
- Android WebView may maintain ordinary browser cache/cookies for logged-in world sessions, not a second message database.
- Install just milestone APKs instead of each commit; use a consistent signing key before promising upgrade-in-place across CI runners.
