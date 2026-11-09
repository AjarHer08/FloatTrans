# Keystore rilis tetap (agar pembaruan bisa menimpa)

Tanpa keystore tetap, GitHub Actions memakai *debug key* baru di setiap build.
Android menganggap APK dengan tanda tangan berbeda sebagai aplikasi berbeda,
jadi versi baru **tidak bisa dipasang menimpa** versi lama (muncul galat
"paket tidak terpasang" / konflik tanda tangan) dan pengguna harus uninstall
dulu.

Dengan **satu keystore rilis tetap** yang dipakai CI setiap kali, tanda tangan
APK konsisten dan Android mengizinkan pemasangan versi baru di atas versi lama.

> Kode dan workflow proyek ini sudah mendukung penandatanganan rilis. Yang
> perlu Anda lakukan hanya: membuat keystore **satu kali**, menyimpannya aman,
> lalu mengisi 4 secret di GitHub.

## Yang Anda butuhkan

- Akses admin ke repositori GitHub (untuk menambah *repository secrets*).
- `keytool` (ada di JDK). Di komputer dengan Android Studio/JDK terpasang,
  `keytool` biasanya sudah tersedia.

## 1. Buat keystore (sekali saja)

Jalankan skrip yang disediakan:

```bash
chmod +x scripts/setup-release-keystore.sh
./scripts/setup-release-keystore.sh
```

Skrip akan:
- membuat file keystore (default: `~/.floattrans-signing/floattrans-release.jks`,
  RSA 2048, berlaku ~27 tahun),
- mencetak nilai `ANDROID_KEYSTORE_BASE64` (isi file keystore yang di-*encode*
  base64, satu baris) untuk ditempel ke GitHub.

Mode non-interaktif (mis. di CI lokal / otomatisasi tepercaya):

```bash
KEYSTORE_PASSWORD='kata-sandi-kuat' KEY_PASSWORD='kata-sandi-kuat' KEY_ALIAS=upload \
  ./scripts/setup-release-keystore.sh --non-interactive
```

Jika Anda sudah punya keystore dan hanya perlu nilai base64-nya:

```bash
./scripts/print-keystore-base64.sh /path/keystore-rilis.jks
```

**Cadangkan** file keystore dan seluruh kata sandinya (password manager /
penyimpanan terenkripsi). Jangan simpan di repo, jangan kirim lewat chat.
Jika keystore hilang, Anda **tidak bisa** menerbitkan APK baru yang menimpa
instalasi yang ditandatangani keystore lama (pengguna harus uninstall sekali
dengan kunci baru).

## 2. Tambahkan 4 secret di GitHub

Buka **Settings → Secrets and variables → Actions → New repository secret**
pada repo `AjarHer08/FloatTrans`, lalu buat secret berikut (nama harus persis):

| Secret | Nilai |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Output base64 satu baris dari skrip (seluruh isi) |
| `ANDROID_KEYSTORE_PASSWORD` | Kata sandi *keystore* |
| `ANDROID_KEY_ALIAS` | Alias kunci (default skrip: `upload`) |
| `ANDROID_KEY_PASSWORD` | Kata sandi *key* (boleh sama dengan kata sandi keystore) |

> Jaga kerahasiaan keempat nilai ini. Siapa pun yang memilikinya dapat
> menandatangani APK atas nama aplikasi Anda.

## 3. Terbitkan rilis bertanda tangan

Buat dan dorong tag versi:

```bash
git tag v1.3.0
git push origin v1.3.0
```

Workflow (`.github/workflows/android.yml`) akan mendeteksi keempat secret dan:
- membangun APK **release** yang ditandatangani keystore tetap (bukan debug),
- melampirkannya ke GitHub Release tag tersebut.

APK yang dipublikasikan bernama seperti
`FloatTrans-arm64-v8a-release-1.3.0.apk`. Fitur **Periksa pembaruan** di dalam
aplikasi otomatis memilih aset `*-release-*` yang sesuai arsitektur perangkat.

## 4. Migrasi pengguna dari APK debug lama (sekali saja)

Pengguna yang memasang APK debug lama (bertanda tangan debug key sementara)
**harus uninstall sekali**, lalu memasang APK release bertanda tangan tetap.
Setelah itu, setiap pembaruan berikutnya yang ditandatangani kunci yang sama
dapat dipasang langsung menimpa tanpa uninstall. Data/pengaturan aplikasi ikut
terhapus saat uninstall (perilaku Android).

## Verifikasi

- Di halaman Actions, run untuk tag harus menjalankan langkah **"Build signed
  release APKs"** (bukan "Build debug APKs").
- Nama aset rilis mengandung `-release-`.
- Di perangkat yang sudah memakai APK bertanda tangan tetap, memasang APK rilis
  baru dengan *versionCode* lebih tinggi berhasil tanpa uninstall.

## Catatan keamanan

- `.jks` / `.keystore` diabaikan oleh `.gitignore`; jangan pernah memaksa commit.
- Skrip menulis keystore ke `~/.floattrans-signing/` (di luar repo) dengan izin
  terbatas. Pindahkan/cadangkan ke lokasi aman pilihan Anda.
- Putar (ganti) keystore hanya jika benar-benar perlu, dan pahami bahwa itu
  memutus jalur pembaruan in-place bagi semua pengguna kunci lama.
