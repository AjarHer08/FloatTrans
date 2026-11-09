#!/usr/bin/env bash
# Mencetak nilai ANDROID_KEYSTORE_BASE64 dari file keystore yang sudah ada.
# Pemakaian: ./scripts/print-keystore-base64.sh /path/keystore-release.jks
set -euo pipefail
FILE="${1:-}"
if [ -z "$FILE" ] || [ ! -f "$FILE" ]; then
  echo "pemakaian: $0 /path/keystore.jks" >&2
  exit 1
fi
base64 -w 0 < "$FILE" 2>/dev/null || base64 < "$FILE" | tr -d '\n'
echo
