#!/usr/bin/env bash
# Membuat keystore rilis tetap untuk FloatTrans dan menampilkan nilai yang
# harus dimasukkan sebagai GitHub Actions repository secrets.
#
# Pemakaian:
#   ./scripts/setup-release-keystore.sh                # interaktif (disarankan)
#   KEYSTORE_PASSWORD=... KEY_PASSWORD=... KEY_ALIAS=upload \
#     ./scripts/setup-release-keystore.sh --non-interactive
#
# Keystore yang dihasilkan TIDAK boleh di-commit. Simpan salinannya di tempat
# aman (password manager / penyimpanan terenkripsi). Jika hilang, Anda tidak
# bisa menerbitkan pembaruan yang menimpa instalasi bertanda tangan lama.
set -euo pipefail

OUT_DIR="${OUT_DIR:-$HOME/.floattrans-signing}"
KEYSTORE_FILE="${KEYSTORE_FILE:-$OUT_DIR/floattrans-release.jks}"
ALIAS="${KEY_ALIAS:-upload}"
DNAME="${DNAME:-CN=FloatTrans, OU=Release, O=FloatTrans, L=Unknown, ST=Unknown, C=ID}"
VALIDITY_DAYS="${VALIDITY_DAYS:-10000}"
NON_INTERACTIVE=0

for arg in "$@"; do
  case "$arg" in
    --non-interactive) NON_INTERACTIVE=1 ;;
    -h|--help)
      sed -n '2,18p' "$0"
      exit 0
      ;;
  esac
done

need() {
  command -v "$1" >/dev/null 2>&1 || { echo "error: '$1' tidak ditemukan di PATH" >&2; exit 1; }
}
need keytool
need base64

mkdir -p "$OUT_DIR"
chmod 700 "$OUT_DIR"

if [ -f "$KEYSTORE_FILE" ]; then
  echo "Keystore sudah ada: $KEYSTORE_FILE"
  echo "Hapus/rename file itu jika ingin membuat yang baru, atau set KEYSTORE_FILE ke path lain."
else
  if [ "$NON_INTERACTIVE" -eq 1 ]; then
    : "${KEYSTORE_PASSWORD:?set KEYSTORE_PASSWORD untuk mode --non-interactive}"
    KEY_PASSWORD="${KEY_PASSWORD:-$KEYSTORE_PASSWORD}"
  else
    echo "== Membuat keystore rilis =="
    echo "Lokasi : $KEYSTORE_FILE"
    echo "Alias  : $ALIAS"
    echo
    read -r -s -p "Password keystore (min 6 karakter): " KEYSTORE_PASSWORD; echo
    read -r -s -p "Password key (Enter = sama dengan keystore): " KEY_PASSWORD; echo
    KEY_PASSWORD="${KEY_PASSWORD:-$KEYSTORE_PASSWORD}"
  fi

  keytool -genkeypair -v \
    -storetype JKS \
    -keystore "$KEYSTORE_FILE" \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity "$VALIDITY_DAYS" \
    -storepass "$KEYSTORE_PASSWORD" \
    -keypass "$KEY_PASSWORD" \
    -dname "$DNAME"

  chmod 600 "$KEYSTORE_FILE"
  echo
  echo "Keystore dibuat: $KEYSTORE_FILE"
fi

echo
echo "== Nilai untuk GitHub repository secrets =="
echo "Buka: https://github.com/AjarHer08/FloatTrans/settings/secrets/actions"
echo "Lalu tambahkan 4 secret berikut (nama persis, tanpa spasi):"
echo
echo "1) ANDROID_KEYSTORE_BASE64"
# base64 dalam satu baris agar mudah ditempel ke UI GitHub
B64=$(base64 -w 0 < "$KEYSTORE_FILE" 2>/dev/null || base64 < "$KEYSTORE_FILE" | tr -d '\n')
echo "$B64"
echo
echo "2) ANDROID_KEYSTORE_PASSWORD"
echo "   -> password keystore yang Anda pilih"
echo "3) ANDROID_KEY_ALIAS"
echo "   -> $ALIAS"
echo "4) ANDROID_KEY_PASSWORD"
echo "   -> password key (sering sama dengan password keystore)"
echo
echo "== Setelah secret ditambahkan =="
echo "- Buat tag rilis baru, contoh: git tag vX.Y.Z && git push origin vX.Y.Z"
echo "- Workflow akan menerbitkan APK *release* bertanda tangan tetap."
echo "- APK itu bisa dipasang menimpa versi lama yang memakai kunci yang sama."
echo "- Pengguna yang masih memakai APK debug lama perlu uninstall sekali,"
echo "  lalu pasang APK release bertanda tangan tetap; setelah itu update"
echo "  berikutnya bisa langsung menimpa."
echo
echo "PENTING: cadangkan $KEYSTORE_FILE dan password-nya. Jangan commit file ini."
