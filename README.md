# nzip

Archiver Android (7z, zip, rar, tar, gz, xz, iso, cab, wim, …) berbasis 7-Zip 26.04.

## Build

Butuh Android SDK + NDK (`sdkmanager "ndk;30.0.16248370"`).

```
./gradlew assembleRelease
```

`preBuild` menjalankan `native/build-7zz.sh`: unduh source 7-Zip, compile `7zz` dengan NDK
(arm64-v8a dengan ASM, armeabi-v7a), lalu taruh hasilnya di `app/src/main/jniLibs/<abi>/lib7zz.so`.
Output: satu APK per ABI + universal di `app/build/outputs/apk/release/`.

### Signing

Release di-sign dengan `nzip-release.jks` (root project). Kredensial dibaca dari `local.properties`:

```
nzip.keystore=nzip-release.jks
nzip.keystore.password=...
nzip.key.alias=nzip
nzip.key.password=...
```

Keystore dan `local.properties` tidak masuk git. Backup keduanya: tanpa keystore yang sama,
update APK tidak bisa di-install di atas versi lama.

## Arsitektur

- Engine = binary `7zz` resmi, dijalankan sebagai proses (`SevenZip.kt`). Dikemas sebagai `lib*.so`
  supaya Android mengekstraknya ke `nativeLibraryDir`, satu-satunya lokasi app yang boleh di-exec.
  Progress dibaca dari output `-bsp1`, cancel = kill proses.
- `ArchiveService` = foreground service, antrean job, progress + tombol Batal di notifikasi.
- UI Compose: `BrowserScreen` (file), `ArchiveScreen` (isi archive), `Dialogs.kt` (extract, buat, password, info).
- Akses penyimpanan lewat `MANAGE_EXTERNAL_STORAGE` (rilis di luar Play Store).

## Lisensi

Kode nzip: Apache-2.0 (`LICENSE`).

Pihak ketiga — 7-Zip: GNU LGPL + BSD 3-clause; kode unRAR berlisensi unRAR (tidak boleh dipakai untuk membuat
kompresor RAR). Lihat `native/build/7z2604-src/DOC/License.txt` setelah build.
