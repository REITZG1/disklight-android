package org.filelight.android

/**
 * Satu node dalam pohon direktori hasil pemindaian.
 * File asli hanyalah anak daun; folder-folder kecil tetap disimpan,
 * sedangkan kumpulan file kecil bisa direpresentasikan sebagai node sintetis
 * "N file lainnya" agar grafik & daftar tetap akurat dan hemat memori.
 */
class Node(
    val path: String,
    var parent: Node?,
    val isDirectory: Boolean,
    nameOverride: String? = null
) {
    val name: String = nameOverride ?: run {
        if (path == "/") "/"
        else path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
    }

    /** Ukuran subtree (folder) atau ukuran file (byte). */
    var size: Long = 0L

    /** Jumlah file langsung di dalam folder ini (bukan rekursif). */
    var fileCount: Long = 0L

    /** Jumlah subfolder langsung di dalam folder ini (bukan rekursif). */
    var dirCount: Long = 0L

    /** Nama tampilan khusus (mis. alias path yang diketik pengguna untuk root scan). */
    var displayName: String? = null

    /** true jika ini node sintetis "N file lainnya". */
    var synthetic: Boolean = false

    val children: MutableList<Node> = ArrayList()

    val isFile: Boolean get() = !isDirectory

    val isRoot: Boolean get() = parent == null

    companion object {
        /** Normalisasi path: hapus trailing slash & duplikat slash. */
        fun normalize(path: String): String {
            if (path == "/") return "/"
            val parts = path.split('/').filter { it.isNotEmpty() }
            return "/" + parts.joinToString("/")
        }

        /** Parent dari sebuah path absolut, atau null bila sudah root "/". */
        fun parentOf(path: String): String? {
            if (path == "/" || path.isEmpty()) return null
            val p = path.trimEnd('/')
            val i = p.lastIndexOf('/')
            return if (i <= 0) "/" else p.substring(0, i)
        }
    }
}
