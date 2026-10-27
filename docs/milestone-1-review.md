# Milestone 1 Review Checklist

## Included
- Native Android launcher HOME entry with styled clock/date screen.
- Searchable app drawer using Android LauncherApps.
- Styled Messages inbox with six **demo-only** fictional contacts.
- Google Messages remains the default SMS/RCS client.
- Optional, ephemeral and local-only notification preview list.
- Direct entry to actual Google Messages.
- Purple visual accent for fictional content.

## Not yet implemented
- Authenticated Living World API and real conversation data.
- Real SMS/RCS full conversation history, outbound replies, SMS replacement.
- Contacts unification.
- Full Samsung-like launcher behavior (widgets, folders, gesture navigation, weather).
- Production tested Android build.

## Manual acceptance checks on test Android device
1. Compile with JDK 17 + Android SDK 35.
2. Install; open as a regular app first.
3. Home renders, app drawer lists installed apps, search filters, launching an app works.
4. Messages screen shows clearly labeled demos and local Google Messages previews only.
5. Without notification access, no previews appear and nothing crashes.
6. Grant notification access to Living World Home in Android Settings (opt-in).
7. Receive a Google Messages notification; refresh and verify only that notification preview appears.
8. Dismiss the real notification; preview disappears.
9. Open Google Messages from shortcut and verify SMS/RCS remains normal.
10. Only after testing, try default HOME selection; verify switching back to Samsung Home.

## Data boundary
No SMS/contacts provider permission. Notification access is Android-wide, but listener code **filters by Google Messages package before reading notification extras**. No notification content storage or network upload. Demo fictional names never map to actual phone numbers.

## Pull requests
PR #1 launcher foundation -> PR #2 Messages shell -> PR #3 read-only notification bridge -> this review styling PR.
Merge in that order, after build/device review.
