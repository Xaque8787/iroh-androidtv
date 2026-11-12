#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
version=0.4.0
checksum=2fa0225a13177f857273a9e00acbccd50690b994f9817acabae7c44e83b22408
mkdir -p app/libs
artifact="app/libs/iroh-client-android-${version}.aar"
if [[ ! -f "$artifact" ]]; then
  curl --fail --location --retry 3 "https://github.com/Xaque8787/iroh-jellyfin/releases/download/v${version}/iroh-client-android-${version}.aar" --output "${artifact}.tmp"
  printf '%s  %s\n' "$checksum" "${artifact}.tmp" | sha256sum --check
  mv "${artifact}.tmp" "$artifact"
fi
printf '%s  %s\n' "$checksum" "$artifact" | sha256sum --check
