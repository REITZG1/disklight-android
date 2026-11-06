package org.filelight.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TreeOpsTest {

    private fun buildTree(): Pair<File, Node> {
        val base = File.createTempFile("filelight-ops", "").apply {
            delete()
            mkdirs()
        }
        File(base, "a.txt").writeBytes(ByteArray(1000))
        val sub = File(base, "sub").apply { mkdirs() }
        File(sub, "b.txt").writeBytes(ByteArray(2000))
        val sub2 = File(sub, "sub2").apply { mkdirs() }
        File(sub2, "c.txt").writeBytes(ByteArray(3000))

        val result = DiskScanner(base.absolutePath, useRoot = false).scan()
        return base to result.root!!
    }

    @Test
    fun detachSubtractsSizesEverywhere() {
        val (base, root) = buildTree()
        try {
            val sub = root.children.first { it.name == "sub" }
            val before = root.size

            assertTrue(TreeOps.detachFromParent(sub))

            // Ukuran berkurang di semua ancestors
            assertEquals(before - sub.size, root.size)
            assertEquals(0, root.children.count { it.name == "sub" })
            assertEquals(0L, root.dirCount)
            val (dirs, files) = TreeOps.recomputeTotals(root)
            assertEquals(1L, files) // a.txt (sub, sub2 & isinya ikut hilang)
            assertEquals(0L, dirs)  // tidak ada folder tersisa
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun detachFileUpdatesCounts() {
        val (base, root) = buildTree()
        try {
            val a = root.children.first { it.name == "a.txt" }
            val before = root.size

            assertTrue(TreeOps.detachFromParent(a))
            assertEquals(before - 1000, root.size)
            assertEquals(0L, root.fileCount)
            val (dirs, files) = TreeOps.recomputeTotals(root)
            assertEquals(2L, dirs)      // sub dan sub2
            assertEquals(2L, files)     // b.txt + c.txt
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun rootCannotBeDetached() {
        val (base, root) = buildTree()
        try {
            assertFalse(TreeOps.detachFromParent(root))
            assertTrue(root.parent == null)
        } finally {
            base.deleteRecursively()
        }
    }
}
