package org.filelight.android

/**
 * Mode pemindaian / penghapusan:
 *
 * - NORMAL  : java.io.File (app uid) — untuk /sdcard dkk.
 * - SHIZUKU : perintah shell yang berjalan sebagai uid starter Shizuku
 *             (shell uid 2000 bila di-start via ADB; uid 0 bila di-start
 *             sebagai root). Privilege lebih besar dari app biasa untuk
 *             membaca/hapus banyak lokasi (mis. /storage/emulated/0,
 *             bahkan /data/media), tetapi tidak bisa membuka /data
 *             milik aplikasi lain.
 * - ROOT    : `su -c '...'` (uid 0) — akses penuh ke semua lokasi.
 */
enum class ScanMode {
    NORMAL, SHIZUKU, ROOT;

    companion object {
        fun fromString(s: String?): ScanMode = when (s) {
            "SHIZUKU" -> SHIZUKU
            "ROOT" -> ROOT
            else -> NORMAL
        }
    }

    val label: String
        get() = when (this) {
            NORMAL -> "Normal"
            SHIZUKU -> "Shizuku"
            ROOT -> "Root"
        }
}
