package org.filelight.android

import java.util.Locale

object SizeUtil {
    private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
    private val LOCALE_ID = Locale("id", "ID")

    /** "1,4 GB" (format Indonesia, basis 1024). */
    fun format(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < UNITS.lastIndex) {
            value /= 1024.0
            unit++
        }
        return String.format(LOCALE_ID, "%.1f %s", value, UNITS[unit])
    }

    /** "1.234.567" */
    fun formatCount(n: Long): String = String.format(LOCALE_ID, "%,d", n)

    /** Persentase "42,3%" */
    fun formatPercent(share: Double): String =
        String.format(LOCALE_ID, "%.1f%%", share * 100.0)

    /** Detik "1,2 s" */
    fun formatDuration(ms: Long): String =
        String.format(LOCALE_ID, "%.1f s", ms / 1000.0)

    /** Pangkas path dari tengah agar muat: "/data/…/org.mozilla.firefox" */
    fun shortPath(path: String, maxLen: Int = 64): String {
        if (path.length <= maxLen) return path
        val keep = (maxLen - 1) / 2
        return path.substring(0, keep) + "…" + path.substring(path.length - (maxLen - keep - 1))
    }
}
