# DiskLight untuk Android

Aplikasi visualisasi pemakaian disk Android, ditulis ulang dari nol dalam **Kotlin**.
Menampilkan pemakaian disk sebagai **grafik sunburst interaktif** gaya Filelight, dengan tiga mode akses:

- **Normal** — akses app biasa (java.io.File)
- **Shizuku (ADB)** — perintah shell sebagai uid shell (2000) lewat [Shizuku](https://shizuku.rikka.app/) — privilege jauh lebih tinggi dari app biasa tanpa perlu root
- **Root** (`su`) — akses penuh ke seluruh filesystem (`/data`, `/system`, `/`)

![Ikon](app/src/main/res/drawable/ic_launcher.xml)

## Fitur

- 🌀 Grafik **sunburst (cincin konsentris)** — sudut segmen proporsional terhadap ukuran; ketuk segmen untuk masuk folder, ketuk pusat lingkaran untuk naik.
- 🗑️ **Hapus file/folder** — tekan-tahan (tap & hold) item di daftar *atau* segmen di grafik, lalu konfirmasi (checkbox PERMANEN). Menghapus mengikuti mode aktif: `su -c 'rm -rf'`, shell Shizuku (uid 2000), atau `File.deleteRecursively()`.
- 🔓 **Mode Root penuh** — `su -c 'find … -printf …'` menelusuri `/data` milik aplikasi lain, `/system`, dan seluruh `/` (Magisk / KernelSU / SuperSU).
- 🛠️ **Mode Shizuku (ADB)** — shell sebagai uid 2000 via Shizuku UserService (AIDL). Bisa membaca `/storage/emulated/0` dan lokasi yang terkunci untuk app biasa, tanpa perlu root. Di perangkat root, Shizuku yang di-start via `adb` berjalan sebagai uid 0.
- 📁 Daftar isi folder: ikon, bar kontribusi, ukuran (KB/MB/GB/TB), persentase — terurut dari yang terbesar.
- 🗂️ Menelusuri direktori tanpa batas (drill-down), tombol naik, tombol kembali hardware untuk naik.
- 💾 Info penyimpanan (`StatFs`): terpakai / tersedia / total untuk filesystem folder yang dipindai.
- 🌗 Tema gelap KDE Breeze.
- Untuk filesystem besar, hanya **8 file terbesar per folder** yang disimpan sebagai node tersendiri; sisanya digabung menjadi node sintetis **"N file lainnya"** — total ukuran tetap **akurat 100%**.

## Pemakaian

1. Buka app — pindai `/sdcard` otomatis saat pertama diluncurkan.
2. Pilih mode lewat tombol **Normal / Shizuku / Root** (tersimpan otomatis).
3. Pilih lokasi: chip cepat `/`, `/data`, `/system`, `/sdcard`, `/storage/emulated/0`, `/data/media` — atau ketik path sendiri.
4. Ketuk segmen grafik untuk drill-down; ketuk item daftar untuk masuk; long-press item untuk detail / hapus.

### Mode Shizuku

Di perangkat **tanpa root**, Shizuku berjalan sebagai shell (uid 2000) — privilege jauh di atas aplikasi biasa. Cara mengaktifkan:

1. Pasang [Shizuku](https://github.com/RikkaApps/Shizuku/releases) (package `moe.shizuku.privileged.api`).
2. Start Shizuku:
   - **Perangkat root**: tombol *Start (for rooted devices)* di aplikasi Shizuku.
   - **Perangkat non-root**: via ADB dari komputer —
     ```bash
     adb shell /data/app/<path>/lib/arm64/libshizuku.so
     ```
     (perintah lengkap tampil di aplikasi Shizuku → *Start by connecting to a computer* → *View command*), atau via Wireless debugging di perangkat.
3. Di DiskLight, pilih tombol **Shizuku** → tap **Allow** pada dialog izin Shizuku.
4. Pindai seperti biasa — footer menampilkan status, mis. `SHIZUKU AKTIF (uid 2000)`.

Catatan: uid 2000 (shell) tidak bisa membaca `/data/data` milik aplikasi lain — untuk itu tetap butuh mode Root.

### Hapus

Tekan-tahan (±0,5 detik) item di daftar atau segmen di grafik → **Hapus** → centang pengakuan PERMANEN → tap **HAPUS**. Ukuran/grafik/total langsung diperbarui tanpa scan ulang. Folder root hasil pemindaian tidak bisa dihapus.

## Cara build

Prasyarat: JDK 17, Android SDK (platform android-35).

```bash
./gradlew :app:assembleDebug
# hasil: app/build/outputs/apk/debug/app-debug.apk

./gradlew :app:testDebugUnitTest   # unit test logika scanner/detach pohon
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Struktur

```
app/src/main/java/org/filelight/android/
├── MainActivity.kt          # UI utama, alur scan, navigasi, dialog hapus
├── SunburstView.kt          # grafik sunburst kustom (Canvas)
├── DiskScanner.kt           # scanner Root (su+find) / Shizuku (shell pipe) / Normal (java.io.File)
├── ShizukuRunner.kt         # bind Shizuku UserService, izin, jalankan perintah shell
├── ShizukuShellService.kt   # UserService Shizuku (Binder AIDL) — menjalankan sh di uid starter
├── ScanMode.kt              # enum Normal / Shizuku / Root
├── Node.kt                  # model pohon direktori
├── ChildAdapter.kt          # adapter daftar isi folder
├── TreeOps.kt               # operasi pohon (lepas node saat hapus, hitung ulang total)
├── SizeUtil.kt              # format ukuran/jumlah/persen (locale id-ID)
└── Su.kt                    # deteksi & escape perintah su
app/src/main/aidl/org/filelight/android/
└── IShizukuShell.aidl       # interface shell Shizuku (ParcelFileDescriptor + id)
```

## Lisensi

Kode ditulis ulang dari nol. Terinspirasi oleh KDE Filelight (GNU GPL v3.0).
Proyek ini adalah karya independen dan tidak berafiliasi dengan KDE e.V.
Dependensi Shizuku: `dev.rikka.shizuku:api:13.1.5` / `provider:13.1.5` (Apache 2.0).
