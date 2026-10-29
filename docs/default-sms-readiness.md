# Default SMS migration release gate

This branch is **not safe to select as the default SMS app**. It deliberately has no default SMS role intent filters, SMS_ROLE request, or outgoing carrier dispatch.

## Implemented in development
- Read-only system SMS history and bubble screen, gated behind default role.
- New SMS composer UI (no sending).
- SMS_DELIVER receiver implementation that decodes and writes an inbound message, **not registered**.
- SMS outbox creation, multipart dispatch code with per-segment sent/delivered PendingIntents, and durable result ledger behind a permanently disabled send flag.
- Google Messages notification bridge remains available while it is the default.
- Live fictional messages continue to use Lovable-backed authentication and portraits.

## Required before enabling role
- SMS_DELIVER receiver declaration + required BROADCAST_SMS protection.
- WAP_PUSH_DELIVER MMS receiver + BROADCAST_WAP_PUSH, correctly parse multipart MMS PDUs and download over correct subscription/carrier MMSC.
- RESPOND_VIA_MESSAGE service with SEND_RESPOND_VIA_MESSAGE permission and SENDTO activity for sms/smsto/mms/mmsto.
- Runtime SMS permissions, explicit RoleManager.ROLE_SMS prompt, reversible handover and backup guidance.
- Reliable incoming persistence and notification scaffold is present but receiver is unregistered; add idempotency, unread notifications and background restrictions validation.
- Enable and test SMS dispatch end to end: persisted provider status + sent/delivered PendingIntent per segment, handle partial failures, retries and dual SIM (implementation present but disabled and untested).
- Working MMS attachments, group messaging, APN/carrier settings, failures, sizes, background receive and send, roaming.
- Proper contact lookup, duplicate handling, thread association and media safety.
- On-device carrier test matrix: Samsung, incoming/outgoing SMS, long/unicode multipart, MMS photos, mixed group threads, airplane mode, reboot, process death, network failure, dual SIM if used.
- Stable signing and upgrades before production adoption.

No change to default SMS role should be made until all checks pass.
