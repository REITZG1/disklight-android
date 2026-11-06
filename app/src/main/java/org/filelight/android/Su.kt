package org.filelight.android

import java.util.concurrent.TimeUnit

/**
 * Util root via `su`. Berjalan pada Magisk / KernelSU / SuperSU yang
 * menyediakan perintah `su -c '...'`.
 */
object Su {

    /** Cek apakah `su` tersedia dan memberi uid=0. Panggil dari background thread. */
    fun isRootAvailable(): Boolean {
        return try {
            val p = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            val out = try {
                p.inputStream.readBytes().toString(Charsets.UTF_8)
            } catch (e: Exception) {
                ""
            }
            val finished = p.waitFor(15, TimeUnit.SECONDS)
            if (!finished) {
                p.destroy()
                false
            } else {
                out.contains("uid=0")
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Escape string agar aman disisipkan dalam tanda kutip satu (single quote) shell. */
    fun escape(path: String): String = "'" + path.replace("'", "'\\''") + "'"
}
