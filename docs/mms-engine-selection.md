# MMS implementation decision — October 2026

## Verified reference implementations

- Fossify Messages: https://github.com/FossifyOrg/Messages
  GPL-3.0 application, actively maintained, working SMS/MMS and groups. Use as behavioral reference; copying GPL application sources entails GPL compliance.
- Fossify mmslib: https://github.com/FossifyOrg/mmslib
  Apache-2.0 library, documented `org.fossify:mmslib:1.0.0`.
  This is a candidate MMS transport/encoding foundation. **Dependency resolution failed in Android builds #82 and #84** for org.fossify:mmslib:1.0.0 against Google/Maven Central/Sonatype snapshots. The dependency was removed to keep CI green. Integrate audited source as a module or locate a verified Maven coordinate before adding it back.
- QUIK SMS: https://github.com/quik-sms/quik
  GPL-3.0 app, actively maintained in 2026. Secondary integration/reference model.

## Integration prerequisites
1. Identify an accessible library source: upstream library source module under its Apache-2.0 license, or a verified binary artifact. Build source with SDK 35 and existing Gradle setup; do not guess Maven coordinates.
2. Integrate MMS PDU encode/decode, provider storage, MMSC configuration and subscription-specific carrier sending using a reviewed API rather than hand-authored partial MMS messages.
3. Register protected MMS delivery and download receivers only after the handlers are functional.
4. Add receive, send, and retry tests with fixture PDUs (single image, text-only MMS, group mixed recipients, GIF, unsupported/oversized media, carrier error).
5. Expose Android SMS role only after all four required handler components, runtime permission flow, and end-to-end Samsung carrier tests are ready.
6. Record Apache-2.0 license and notices for mmslib; do not ingest GPL app files absent deliberate licensing decision.

Current app remains notification-based for real messages and keeps Google Messages default. It is not a default SMS/MMS application. Do not toggle SEND_ENABLED until carrier tests.
