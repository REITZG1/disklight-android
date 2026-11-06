package org.filelight.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DiskScannerTest {

    private fun createTree(base: File) {
        base.mkdirs()
        File(base, "a.txt").writeBytes(ByteArray(1000))
        val sub = File(base, "sub").apply { mkdirs() }
        File(sub, "b.txt").writeBytes(ByteArray(2000))
        val sub2 = File(sub, "sub2").apply { mkdirs() }
        File(sub2, "c.txt").writeBytes(ByteArray(3000))
        File(sub2, "empty").mkdirs()
        File(base, "other.bin").writeBytes(ByteArray(400))
    }

    @Test
    fun scanBuildsCorrectTree() {
        val base = File.createTempFile("filelight-test", "").apply {
            delete()
            mkdirs()
        }
        try {
            createTree(base)
            val result = DiskScanner(base.absolutePath, useRoot = false).scan()

            assertFalse("scan must succeed", result.root == null)
            val root = result.root!!
            assertEquals(6400L, root.size)
            assertEquals(2L, root.fileCount) // a.txt + other.bin
            assertEquals(1L, root.dirCount)  // sub

            val sub = root.children.first { it.name == "sub" }
            assertEquals(5000L, sub.size)
            assertEquals(1L, sub.fileCount)  // b.txt
            assertEquals(1L, sub.dirCount)   // sub2

            val sub2 = sub.children.first { it.name == "sub2" }
            assertEquals(3000L, sub2.size)
            assertTrue(sub2.children.any { it.name == "empty" && it.isDirectory })

            // Semua anak terurut dari besar ke kecil
            assertEquals(6400L, root.children.sumOf { it.size })

            // Path non-existent
            val missing = DiskScanner(File(base, "nope").absolutePath, useRoot = false).scan()
            assertEquals(true, missing.pathMissing)

            assertTrue(result.durationMs >= 0)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun smallFileCapAndSyntheticNode() {
        val base = File.createTempFile("filelight-test-small", "").apply {
            delete()
            mkdirs()
        }
        try {
            // 20 file kecil — hanya 8 terbesar yang disimpan, sisanya disintesis
            for (i in 1..20) {
                File(base, "f%02d.bin".format(i)).writeBytes(ByteArray(i * 100))
            }
            val result = DiskScanner(base.absolutePath, useRoot = false).scan()
            val root = result.root!!

            val total = root.children.sumOf { it.size }
            val expected = (1..20).sumOf { it * 100 }.toLong()
            assertEquals(expected, total)

            val synthetic = root.children.firstOrNull { it.synthetic }
            assertNotNull("node sintetis harus ada (20 file > 8 terbesar)", synthetic)
            assertEquals(12L, synthetic!!.fileCount)
            assertTrue(synthetic.size > 0)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun normalizeHandlesSlashes() {
        assertEquals("/", Node.normalize("/"))
        assertEquals("/data", Node.normalize("/data/"))
        assertEquals("/data/app", Node.normalize("//data//app//"))
        assertEquals("/a/b", Node.normalize("/a/b"))
        assertEquals("/", Node.parentOf("/data"))
        assertEquals("/data", Node.parentOf("/data/app"))
        assertEquals(null, Node.parentOf("/"))
    }
}
