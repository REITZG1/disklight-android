package org.filelight.android

import java.util.ArrayDeque

/**
 * Operasi pohon direktori (tanpa dependensi Android) — dipakai saat penghapusan.
 */
object TreeOps {

    /**
     * Lepas [node] dari parent-nya dan kurangi ukurannya dari seluruh leluhur
     * (termasuk root). Menghasilkan false bila node adalah root tertinggi.
     */
    fun detachFromParent(node: Node): Boolean {
        val parent = node.parent ?: return false
        if (!parent.children.remove(node)) return false

        val size = node.size
        var cur: Node? = parent
        while (cur != null) {
            cur.size = (cur.size - size).coerceAtLeast(0)
            cur = cur.parent
        }
        if (node.isDirectory) {
            parent.dirCount = (parent.dirCount - 1).coerceAtLeast(0)
        } else {
            parent.fileCount = (parent.fileCount - 1).coerceAtLeast(0)
        }
        return true
    }

    /**
     * Hitung ulang (folder, file) dari pohon — file dihitung lewat fileCount
     * langsung tiap folder (node sintetis ikut terhitung), folder dihitung dari
     * jumlah node isDirectory tanpa root itu sendiri.
     */
    fun recomputeTotals(root: Node): Pair<Long, Long> {
        var dirs = 0L
        var files = 0L
        val stack = ArrayDeque<Node>()
        stack.add(root)
        while (true) {
            val n = stack.poll() ?: break
            if (!n.isDirectory) continue
            dirs++
            files += n.fileCount
            for (c in n.children) stack.add(c)
        }
        return Pair((dirs - 1).coerceAtLeast(0), files)
    }
}
