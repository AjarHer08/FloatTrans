# FloatTrans — penerjemah melayang untuk manhwa, manga, manhua & komik Inggris

Aplikasi Android yang menampilkan gelembung melayang di atas aplikasi baca komik apa pun.
Buka halamannya, dan aplikasi akan:

1. menangkap layar lewat izin tangkap layar Android,
2. membaca teks dengan OCR ML Kit (Latin, Jepang, Korea, Tionghoa),
3. menerjemahkannya langsung di perangkat (tanpa server, tanpa API key),
4. menggambar hasil terjemahan tepat di atas balon teks aslinya dengan gaya lettering komik.

Cocok untuk manhwa (Korea), manga (Jepang), manhua (Tionghoa), dan komik versi Inggris.

## Fitur

- **Otomatis saat pindah halaman/chapter** — mode otomatis memantau perubahan layar (bukan timer OCR), jadi begitu Anda swipe ke halaman berikutnya, scroll webtoon, atau masuk chapter baru, terjemahan muncul sendiri tanpa menekan apa pun. Halaman yang masih dimuat diberi jeda sebentar agar tidak di-OCR setengah jadi.
- **Overlay melayang** yang bisa digeser, tanpa mengganggu aplikasi baca komik di belakangnya.
- **Terjemahan menimpa balon teks** — baris OCR digabung per balon lalu diterjemahkan sebagai satu kalimat, agar hasilnya utuh.
- **Gaya lettering komik** (bawaan "Otomatis"): font komik (Comic Neue), teks rata tengah dan dipaskan ke dalam balon, tinta hitam pada balon terang dan tinta putih bergaris tepi pada balon gelap. Balon asli ditutup dengan warna balon itu sendiri, jadi sisa huruf aslinya tidak tembus. Tersedia juga gaya kotak warna balon, kotak + garis tepi, atau tanpa latar.
- **Mesin terjemahan bisa dipilih**: Google online (paling akurat untuk Korea/Tionghoa/Jepang), otomatis (Google dulu, cadangan offline ML Kit), atau offline penuh.
- **Cek pembaruan bawaan**: tombol Periksa pembaruan membaca GitHub Releases, mengunduh APK sesuai arsitektur HP, lalu membuka pemasang Android — tanpa perlu ke browser.
- **Deteksi aksara otomatis** (Latin / kana / hangul / hanzi) atau pilih manual agar lebih cepat.
- **Deteksi bahasa otomatis** + pilihan bahasa target (Indonesia sebagai bawaan).
- **Menu cepat** saat gelembung ditahan: terjemahkan sekarang, matikan mode otomatis, bersihkan, buka pengaturan, hentikan sesi.
- **Kecepatan deteksi halaman, ukuran teks, dan tampilan teks asli** bisa diatur.
- OCR dan pengenalan bahasa berjalan **on-device**. Tangkapan layar tidak pernah dikirim ke mana pun; pada mesin online hanya teks hasil OCR yang dikirim ke Google untuk diterjemahkan.

## Persyaratan

- Android 6.0 (API 23) sampai Android 17.
- Izin "tampil di atas aplikasi lain" (overlay).
- Izin tangkap layar yang diminta Android setiap kali sesi dimulai (wajib sejak Android 14).
- Internet **hanya** untuk mengunduh model terjemahan bahasa baru (sekali per bahasa). OCR dan terjemahan setelahnya sepenuhnya offline.

## Instalasi

1. Unduh APK dari tab **[Releases](https://github.com/AjarHer08/FloatTrans/releases)**.
2. Pilih APK sesuai perangkat:
   - `...-arm64-v8a-...apk` — hampir semua HP Android modern (paling disarankan)
   - `...-armeabi-v7a-...apk` — HP 32-bit / Android lama
   - `...-x86_64-...apk` — emulator / Chromebook
   - `...-universal-...apk` — cadangan untuk semua perangkat
3. Izinkan "install dari sumber tidak dikenal" jika diminta, lalu pasang.
4. Buka FloatTrans → **Izinkan tampil di atas aplikasi lain** → **Mulai** → setujui dialog tangkap layar.

### Pembaruan

- Tekan **Periksa pembaruan** di layar utama (otomatis dicek sekali saat aplikasi dibuka). Aplikasi memilih APK sesuai arsitektur perangkat, mengunduhnya, lalu membuka pemasang Android.
- **Agar pembaruan bisa dipasang menimpa tanpa uninstall**, repo harus memakai keystore rilis tetap — ikuti [docs/signing-rilis.md](docs/signing-rilis.md). Tanpa itu, APK dari CI bertanda tangan debug sementara dan Android menolak menimpanya (aplikasi akan menampilkan panduan pasang ulang).
- Jika masih memakai APK debug lama: uninstall sekali, pasang APK `*-release-*` bertanda tangan tetap, lalu pembaruan berikutnya bisa langsung menimpa.

## Cara pakai

1. Tekan **Mulai**, setujui permintaan tangkap layar. Gelembung `文A` muncul di tepi layar.
2. Buka aplikasi baca komik Anda. Halaman yang terbuka langsung diterjemahkan.
3. Pindah halaman, scroll webtoon, atau buka chapter berikutnya — terjemahan baru muncul sendiri (mode otomatis).
4. Ketuk gelembung = terjemahkan ulang sekali. Tahan gelembung = menu (mode otomatis, bersihkan, pengaturan, hentikan).
5. Lingkaran hijau pada gelembung berarti mode otomatis aktif.
6. Jika hasil terasa kurang tepat, pilih **Mesin terjemahan → Google online** agar konteks kalimat antar balon ikut diperhitungkan.

Tips akurasi:

- Kalau sering salah aksara, pilih aksara manual (mis. **Korea (hangul)** untuk manhwa).
- Perbesar sedikit halaman di aplikasi baca; OCR lebih akurat pada teks besar.
- Pilih **Kecepatan deteksi: Cepat** untuk pindah halaman instan, atau **Hemat baterai** di perangkat yang lebih lambat.

## Arsitektur singkat

| Bagian | Berkas |
| --- | --- |
| Layanan latar + deteksi pindah halaman + orkestrasi | `app/src/main/java/com/floattrans/app/CaptureService.kt` |
| Tangkap layar & sidik jari perubahan halaman | `app/src/main/java/com/floattrans/app/capture/ScreenCapturer.kt` |
| OCR multi-aksara + auto-deteksi | `app/src/main/java/com/floattrans/app/core/OcrEngine.kt` |
| Penggabungan baris OCR per balon | `app/src/main/java/com/floattrans/app/core/TextGrouper.kt` |
| Terjemahan online (Google) + cadangan offline ML Kit | `app/src/main/java/com/floattrans/app/core/GoogleTranslator.kt`, `core/TranslateEngine.kt` |
| Cek pembaruan & unduh APK dari GitHub Releases | `app/src/main/java/com/floattrans/app/update/UpdateChecker.kt` |
| Gelembung, menu, kanvas terjemahan bergaya komik | `app/src/main/java/com/floattrans/app/overlay/` |
| Layar pengaturan, izin, dan pembaruan | `app/src/main/java/com/floattrans/app/MainActivity.kt` |

Overlay sendiri ikut tertangkap layar, jadi aplikasi menyembunyikan jendela overlay selama ±140 ms pada setiap pengambilan frame, lalu menampilkannya kembali.

## Build

Build lokal (butuh JDK 17 + Android SDK 35):

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/
```

Build lewat GitHub Actions (otomatis):

- Setiap push ke `main` / `master`, pull request, atau manual `workflow_dispatch` → APK diunggah sebagai artifact.
- Push tag `vX.Y.Z` → APK otomatis dilampirkan ke GitHub Release:

  ```bash
  git tag v1.2.2
  git push origin v1.2.2
  ```

- **Keystore rilis tetap (disarankan):** tambahkan 4 secret `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` → CI menerbitkan APK `*-release-*` bertanda tangan tetap yang bisa dipasang menimpa versi lama. Panduan lengkap + skrip pembuat keystore: [docs/signing-rilis.md](docs/signing-rilis.md). Tanpa secret ini (atau pada pull request) CI menghasilkan APK debug.

## Batasan yang perlu diketahui

- Konten berproteksi `FLAG_SECURE` (video streaming berbayar, aplikasi banking) tidak bisa ditangkap — layar akan hitam. Aplikasi baca komik biasanya tidak memakai proteksi ini.
- Mesin **Google online** butuh internet; tanpa koneksi aplikasi otomatis memakai ML Kit offline (kualitas lebih rendah, dan model bahasa baru diunduh sekali per pasangan bahasa).
- OCR terbaik pada teks horizontal. Teks vertikal atau efek tipografi ekstrem bisa salah baca.
- Jika layar diputar saat sesi berjalan, hentikan dan mulai ulang sesi agar ukuran tangkapan ikut menyesuaikan.
- APK debug dari CI (tanpa keystore tetap) ditandatangani dengan debug key sementara dan **tidak bisa** dipasang menimpa versi lama. Gunakan APK `*-release-*` dari keystore tetap agar pembaruan menimpa — lihat [docs/signing-rilis.md](docs/signing-rilis.md).
- Android versi baru menampilkan chip "sedang menangkap layar"; menekan tombol berhenti di situ akan menghentikan sesi FloatTrans juga.

## Privasi

Tidak ada server milik proyek ini, tidak ada akun, tidak ada analitik. Tangkapan layar diproses di memori perangkat dan dibuang setelah diterjemahkan — gambar layar tidak pernah diunggah. Koneksi keluar hanya: (1) teks hasil OCR ke Google Translate saat mesin online aktif, (2) unduhan model ML Kit untuk mode offline, dan (3) permintaan ke GitHub Releases saat memeriksa pembaruan. Pilih **Offline ML Kit** bila tidak ingin ada teks yang dikirim ke Google.

## Lisensi font

Font **Comic Neue** (Bold/Regular) dibundel di `app/src/main/res/font/` dan dilisensikan di bawah SIL Open Font License 1.1 — lihat `licenses/ComicNeue-OFL.txt`.
