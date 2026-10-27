# Unified Messages — staged native integration

## Current prototype (safe stage)
- Android-native Messages shell and visible fictional sample threads.
- Fictional rows open the existing Living World service in the browser until authenticated deep links are implemented.
- Explicit shortcut opens real Google Messages as a separate app.
- No SMS permissions, no reading actual messages, no sending, and no default SMS role change.
- Subtle purple colors distinguish fictional conversations; no prominent badges.

## Next stages
1. Connect authenticated Living World API, fetch conversation list, open the correct thread, and handle incoming notifications.
2. Build Android contact source adapters; user-approved read-only contacts access.
3. Explore real-SMS integration on a test device, with explicit consent and permission requests.
4. Only after a tested SMS/MMS app implementation, evaluate the Android default SMS role.
5. Preserve Google Messages for RCS if no supported interoperable RCS path exists.

Never silently convert an existing real thread to AI, route a real text to the fictional backend, or request SMS permissions without consent.
