# Samsung Galaxy Z Fold6 / T-Mobile MMS carrier test gate

The download pipeline is now coded but is **not yet approved for real-world use**.
Do not merge, enable `DOWNLOAD_ENABLED`, register WAP_PUSH_DELIVER, or replace
Google Messages as default until device tests and loss/retry handling pass.

## Code path
1. Default-SMS WAP push (future registered receiver) decodes NotificationInd using pinned Fossify mmslib.
2. SIM subscription is extracted from Android broadcast; avoid a hardcoded T-Mobile APN/MMSC.
3. `SmsManager.createForSubscriptionId(subId).downloadMultimediaMessage` writes a retrieved PDU to a private content provider.
4. Completion receiver checks platform result and payload bounds, parses RetrieveConf, and persists it via upstream PduPersister.
5. UI reads provider SMS/MMS history.

## Still blocking live device test
- Durable duplicate suppression keyed by transaction ID/content location and successful storage ID.
- Carrier error, no-network and process-death retry queue without losing downloaded content.
- Verify provider URI grants across Samsung platform carrier service; avoid premature raw-PDU cleanup on persistence failures.
- Secure validation of media types and sizes before accepting attachments and group membership.
- MMS send/group compose, delivery failure UI and media rendering.
- RoleManager-compatible manifest components, permissions UX, switch-back instructions and stable signing.
- Automated instrumentation tests and private carrier end-to-end test, including send/receive, mixed group chats, airplane mode, roaming, Wi-Fi calling, process death, reboot and duplicate WAP broadcasts.

Keep T-Mobile carrier settings under Android/SubscriptionManager control and avoid secrets or contact/picture contents in logs.
