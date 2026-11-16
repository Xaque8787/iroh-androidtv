#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
version=0.4.0
checksum=54349225b04e397cae6dc83c9b6ff0fa5cd297abf427042ca63a73077516cb09
mkdir -p app/libs
artifact="app/libs/kausway-client-android-${version}.aar"
if [[ ! -f "$artifact" ]]; then
  curl --fail --location --retry 3 "https://github.com/Xaque8787/Kausway/releases/download/v${version}/kausway-client-android-${version}.aar" --output "${artifact}.tmp"
  printf '%s  %s\n' "$checksum" "${artifact}.tmp" | sha256sum --check
  mv "${artifact}.tmp" "$artifact"
fi
printf '%s  %s\n' "$checksum" "$artifact" | sha256sum --check
