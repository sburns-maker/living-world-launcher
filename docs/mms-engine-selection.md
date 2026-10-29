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

## Pinned source integration (current branch)

Android CI checks out FossifyOrg/mmslib at commit `ebfbb30d616631eca86f9cd257f627bdb636c1c2`
under `vendor/mmslib` and compiles `:mmslib` as a local Android library module.
The library is **not** stored in our Git repository and the app's carrier-facing MMS
send/receive paths are **not yet wired up**. Pinning prevents silently consuming
unreviewed upstream commits.

For local Android Studio work, run before importing Gradle:

```bash
git clone https://github.com/FossifyOrg/mmslib.git vendor/mmslib
git -C vendor/mmslib checkout ebfbb30d616631eca86f9cd257f627bdb636c1c2
```

Include upstream LICENSE and NOTICE attribution in distribution packages.
Do not release or choose this app as default SMS merely because the source module compiles.

## Pinned source integration — verified
- `scripts/fetch-mmslib.sh` checks out FossifyOrg/mmslib at `ebfbb30d616631eca86f9cd257f627bdb636c1c2` into the ignored `vendor/mmslib` directory.
- Gradle includes the upstream `:library` source as local `:mmslib`; CI fetches the source before compiling.
- GitHub Actions Android Build #106 succeeded with this source module.
- `MmsTransport.kt` now uses the upstream `Settings`, `Message`, and `Transaction` image-sending API with system MMS transport. Its send gate is **false** until full MMS receive/download, provider writes, callbacks, and Samsung carrier validation are implemented.
- This is a buildable MMS *foundation*, not a working default MMS app. Preserve Apache-2.0 copyright notices when distributing.
