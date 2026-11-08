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
- **Gaya lettering komik** (bawaan "Otomatis"): font komik (Comic Neue), teks rata tengah dan dipaskan ke dalam balon, tinta hitam pada balon terang dan tinta putih bergaris tepi pada balon gelap. Tersedia juga gaya kotak warna balon, kotak + garis tepi, atau tanpa latar.
- **Deteksi aksara otomatis** (Latin / kana / hangul / hanzi) atau pilih manual agar lebih cepat.
- **Deteksi bahasa otomatis** + pilihan bahasa target (Indonesia sebagai bawaan).
- **Menu cepat** saat gelembung ditahan: terjemahkan sekarang, matikan mode otomatis, bersihkan, buka pengaturan, hentikan sesi.
- **Kecepatan deteksi halaman, ukuran teks, dan tampilan teks asli** bisa diatur.
- Semua proses berjalan **on-device**; tangkapan layar tidak pernah dikirim ke mana pun.

## Persyaratan

- Android 6.0 (API 23) sampai Android 17.
- Izin "tampil di atas aplikasi lain" (overlay).
- Izin tangkap layar yang diminta Android setiap kali sesi dimulai (wajib sejak Android 14).
- Internet **hanya** untuk mengunduh model terjemahan bahasa baru (sekali per bahasa). OCR dan terjemahan setelahnya sepenuhnya offline.

## Instalasi

1. Unduh APK dari tab **Releases** di repo ini, atau dari **Actions → Build APK → artifact** pada build terakhir.
2. Pilih APK sesuai perangkat:
   - `...-arm64-v8a-...apk` — hampir semua HP Android modern (paling disarankan)
   - `...-armeabi-v7a-...apk` — HP 32-bit / Android lama
   - `...-x86_64-...apk` — emulator / Chromebook
   - `...-universal-...apk` — cadangan untuk semua perangkat
3. Izinkan "install dari sumber tidak dikenal" jika diminta, lalu pasang.
4. Buka FloatTrans → **Izinkan tampil di atas aplikasi lain** → **Mulai** → setujui dialog tangkap layar.

## Cara pakai

1. Tekan **Mulai**, setujui permintaan tangkap layar. Gelembung `文A` muncul di tepi layar.
2. Buka aplikasi baca komik Anda. Halaman yang terbuka langsung diterjemahkan.
3. Pindah halaman, scroll webtoon, atau buka chapter berikutnya — terjemahan baru muncul sendiri (mode otomatis).
4. Ketuk gelembung = terjemahkan ulang sekali. Tahan gelembung = menu (mode otomatis, bersihkan, pengaturan, hentikan).
5. Lingkaran hijau pada gelembung berarti mode otomatis aktif.

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
| Terjemahan on-device + deteksi bahasa | `app/src/main/java/com/floattrans/app/core/TranslateEngine.kt` |
| Gelembung, menu, kanvas terjemahan bergaya komik | `app/src/main/java/com/floattrans/app/overlay/` |
| Layar pengaturan & izin | `app/src/main/java/com/floattrans/app/MainActivity.kt` |

Overlay sendiri ikut tertangkap layar, jadi aplikasi menyembunyikan jendela overlay selama ±140 ms pada setiap pengambilan frame, lalu menampilkannya kembali.

## Build

Build lokal (butuh JDK 17 + Android SDK 35):

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/
```

Build lewat GitHub Actions (otomatis):

- Setiap push ke `main` / `master`, pull request, atau manual `workflow_dispatch` → APK diunggah sebagai artifact.
- Push tag `v1.1.0` → APK otomatis dilampirkan ke GitHub Release:

  ```bash
  git tag v1.1.0
  git push origin v1.1.0
  ```

- APK release bertanda tangan resmi (opsional): tambahkan secret `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. Tanpa secret ini CI tetap menghasilkan APK debug yang bisa dipasang.

## Batasan yang perlu diketahui

- Konten berproteksi `FLAG_SECURE` (video streaming berbayar, aplikasi banking) tidak bisa ditangkap — layar akan hitam. Aplikasi baca komik biasanya tidak memakai proteksi ini.
- Model terjemahan diunduh sekali per pasangan bahasa; tanpa internet dan tanpa model, terjemahan gagal (teks asli tetap tampil).
- OCR terbaik pada teks horizontal. Teks vertikal atau efek tipografi ekstrem bisa salah baca.
- Jika layar diputar saat sesi berjalan, hentikan dan mulai ulang sesi agar ukuran tangkapan ikut menyesuaikan.
- APK dari Actions ditandatangani dengan debug keystore yang dibuat ulang di setiap build, sehingga APK baru **tidak bisa** dipasang menimpa versi lama: uninstall dulu, atau isi 4 secret keystore agar CI menghasilkan APK release bertanda tangan tetap.
- Android versi baru menampilkan chip "sedang menangkap layar"; menekan tombol berhenti di situ akan menghentikan sesi FloatTrans juga.

## Privasi

Tidak ada server, tidak ada akun, tidak ada analitik. Tangkapan layar diproses di memori perangkat dan dibuang setelah diterjemahkan. Satu-satunya koneksi keluar adalah unduhan model ML Kit dari Google.

## Lisensi font

Font **Comic Neue** (Bold/Regular) dibundel di `app/src/main/res/font/` dan dilisensikan di bawah SIL Open Font License 1.1 — lihat `licenses/ComicNeue-OFL.txt`.
