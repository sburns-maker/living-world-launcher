#!/usr/bin/env bash
set -euo pipefail
# Apache-2.0 upstream source, pinned for reproducible Android builds.
SOURCE="https://github.com/FossifyOrg/mmslib.git"
REV="ebfbb30d616631eca86f9cd257f627bdb636c1c2"
DEST="vendor/mmslib"
if [[ -d "$DEST/.git" ]]; then
  actual="$(git -C "$DEST" rev-parse HEAD)"
  [[ "$actual" == "$REV" ]] || { echo "Unexpected MMS source checkout: $actual"; exit 1; }
  exit 0
fi
mkdir -p vendor
git clone --filter=blob:none --no-checkout "$SOURCE" "$DEST"
git -C "$DEST" checkout --detach "$REV"
actual="$(git -C "$DEST" rev-parse HEAD)"
[[ "$actual" == "$REV" ]] || { echo "MMS source SHA verification failed"; exit 1; }
test -f "$DEST/library/build.gradle.kts"
echo "Pinned mmslib source verified: $REV"
