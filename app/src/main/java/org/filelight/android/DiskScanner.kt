package org.filelight.android

import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque

data class ScanResult(
    val root: Node?,
    val rootUnavailable: Boolean,
    val shizukuUnavailable: Boolean = false,
    val pathMissing: Boolean,
    val totalBytes: Long,
    val totalFiles: Long,
    val totalDirs: Long,
    val skippedDirs: Int,
    val durationMs: Long,
    val usedRoot: Boolean,
    val usedShizuku: Boolean = false,
    val cancelled: Boolean
)

/**
 * Pemindai disk — dua jalur:
 *
 *  1. **Root** (`su -c 'find … -printf …'`): menelusuri seluruh filesystem
 *     termasuk `/data` milik aplikasi lain. Format output portabel di toybox
 *     (Android) dan GNU findutils: `<tipe><SOH><ukuran byte><SOH><path><STX>`
 *     dengan byte mentah 0x01 (SOH) / 0x02 (STX).
 *
 *  2. **Non-root** (java.io.File): fallback untuk penyimpanan bersama.
 *
 * Keduanya mengagregasi ke struktur yang sama dan menghasilkan pohon
 * yang sama. Untuk menghemat memori pada filesystem besar, hanya 8 file
 * terbesar per folder yang disimpan sebagai node tersendiri; sisanya
 * digabung menjadi node sintetis "N file lainnya" yang ukurannya tetap
 * akurat.
 */
class DiskScanner(
    private val path: String,
    private val mode: ScanMode,
    private val onProgress: (files: Long, bytes: Long) -> Unit = { _, _ -> },
    /** Executor perintah shell untuk mode SHIZUKU: cmd -> InputStream stdout (null = gagal). */
    private val shizukuExecutor: ((String) -> java.io.InputStream?)? = null
) {
    constructor(
        path: String,
        useRoot: Boolean,
        onProgress: (files: Long, bytes: Long) -> Unit = { _, _ -> }
    ) : this(path, if (useRoot) ScanMode.ROOT else ScanMode.NORMAL, onProgress, null)
    @Volatile
    private var process: Process? = null

    @Volatile
    private var activeStream: java.io.InputStream? = null

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
        try {
            activeStream?.close()
        } catch (_: Exception) {
        }
    }

    /** Byte pemisah record (STX) yang secara mentah dicetak oleh -printf. */
    private val RS: Byte = 0x02

    private fun findCommand(scanRoot: String): String {
        val ep = Su.escape(scanRoot)
        val fs = 0x01.toChar()
        val rs = 0x02.toChar()
        return "find $ep -type d -printf 'd${fs}%s${fs}%p${rs}' 2>/dev/null; " +
            "find $ep \\( -type f -o -type l \\) -printf 'f${fs}%s${fs}%p${rs}' 2>/dev/null"
    }

    private fun readFindStream(input: java.io.InputStream, scanRoot: String, agg: Aggregator) {
        var lines = 0L
        val buf = ByteArray(1 shl 16)
        val rec = java.io.ByteArrayOutputStream(512)
        while (!cancelled) {
            val n = try {
                input.read(buf)
            } catch (e: Exception) {
                -1
            }
            if (n <= 0) break
            for (i in 0 until n) {
                val b = buf[i]
                if (b == RS) {
                    handleRecord(rec, agg)
                    lines++
                    if ((lines and 0x1FF) == 0L) {
                        onProgress(agg.fileCountTotal, agg.dirSize[scanRoot] ?: 0L)
                    }
                    rec.reset()
                } else {
                    rec.write(b.toInt())
                }
            }
        }
        if (!cancelled && rec.size() > 0) handleRecord(rec, agg)
    }

    private fun canonicalize(scanRoot0: String): String {
        // NORMAL & ROOT memakai File.canonical; SHIZUKU dipanggil terpisah.
        return try {
            Node.normalize(File(Node.normalize(scanRoot0)).canonicalFile.absolutePath)
        } catch (e: Exception) {
            Node.normalize(scanRoot0)
        }
    }

    private fun canonicalizeShizuku(scanRoot0: String): String {
        val exec = shizukuExecutor ?: return Node.normalize(scanRoot0)
        return try {
            val out = exec("readlink -f ${Su.escape(Node.normalize(scanRoot0))} 2>/dev/null | head -c 512")?.use {
                it.readBytes().toString(Charsets.UTF_8).trim()
            }
            if (out.isNullOrEmpty() || !out.startsWith("/")) Node.normalize(scanRoot0)
            else Node.normalize(out.lines().first().trim())
        } catch (e: Exception) {
            Node.normalize(scanRoot0)
        }
    }

    fun scan(): ScanResult {
        val startMs = System.currentTimeMillis()
        return when (mode) {
            ScanMode.ROOT -> {
                val scanRoot = canonicalize(path)
                val agg = Aggregator(scanRoot)
                if (!Su.isRootAvailable()) {
                    return ScanResult(
                        root = null, rootUnavailable = true, pathMissing = false,
                        totalBytes = 0, totalFiles = 0, totalDirs = 0,
                        skippedDirs = 0, durationMs = 0, usedRoot = true, cancelled = false
                    )
                }
                val ok = scanWithSu(scanRoot, agg)
                if (cancelled) return cancelledResult(agg, ScanMode.ROOT, startMs)
                if (!ok) {
                    return ScanResult(
                        root = null, rootUnavailable = false, pathMissing = true,
                        totalBytes = 0, totalFiles = 0, totalDirs = 0,
                        skippedDirs = 0, durationMs = 0, usedRoot = true, cancelled = false
                    )
                }
                finish(agg, scanRoot, ScanMode.ROOT, startMs)
            }
            ScanMode.SHIZUKU -> {
                val exec = shizukuExecutor
                if (exec == null) {
                    return ScanResult(
                        root = null, rootUnavailable = false, shizukuUnavailable = true, pathMissing = false,
                        totalBytes = 0, totalFiles = 0, totalDirs = 0,
                        skippedDirs = 0, durationMs = 0, usedRoot = false, usedShizuku = true, cancelled = false
                    )
                }
                val scanRoot = canonicalizeShizuku(path)
                val agg = Aggregator(scanRoot)
                val ok = scanWithShizuku(scanRoot, agg)
                if (cancelled) return cancelledResult(agg, ScanMode.SHIZUKU, startMs)
                if (!ok) {
                    if (agg.dirSet.isEmpty() && agg.fileCountTotal == 0L) {
                        return ScanResult(
                            root = null, rootUnavailable = false, shizukuUnavailable = true, pathMissing = false,
                            totalBytes = 0, totalFiles = 0, totalDirs = 0,
                            skippedDirs = 0, durationMs = 0, usedRoot = false, usedShizuku = true, cancelled = false
                        )
                    }
                    return ScanResult(
                        root = null, rootUnavailable = false, pathMissing = true,
                        totalBytes = 0, totalFiles = 0, totalDirs = 0,
                        skippedDirs = 0, durationMs = 0, usedRoot = false, usedShizuku = true, cancelled = false
                    )
                }
                finish(agg, scanRoot, ScanMode.SHIZUKU, startMs)
            }
            ScanMode.NORMAL -> {
                val scanRoot = canonicalize(path)
                val agg = Aggregator(scanRoot)
                val ok = scanWithJava(scanRoot, agg)
                if (cancelled) return cancelledResult(agg, ScanMode.NORMAL, startMs)
                if (!ok) {
                    return ScanResult(
                        root = null, rootUnavailable = false, pathMissing = true,
                        totalBytes = 0, totalFiles = 0, totalDirs = 0,
                        skippedDirs = 0, durationMs = 0, usedRoot = false, cancelled = false
                    )
                }
                finish(agg, scanRoot, ScanMode.NORMAL, startMs)
            }
        }
    }

    /* ─────────────────────────── scan lewat su ─────────────────────────── */

    private fun scanWithSu(scanRoot: String, agg: Aggregator): Boolean {
        // 'find' menelusuri seluruh pohon. Dua pass (direktori & file) karena toybox
        // tidak punya %y. FORMAT: <tipe><SOH><ukuran byte><SOH><path><STX> dengan
        // byte mentah 0x01/0x02 — portabel di toybox & GNU findutils (backslash-escape
        // TIDAK diproses toybox di banyak perangkat).
        val cmd = findCommand(scanRoot)
        val proc = try {
            ProcessBuilder("su", "-c", cmd).start()
        } catch (e: Exception) {
            return false
        }
        process = proc

        try {
            proc.inputStream.use { input ->
                activeStream = input
                readFindStream(input, scanRoot, agg)
            }
        } catch (e: Exception) {
            // Stream rusak / dibatalkan — gunakan data parsial yang terkumpul.
        }
        try {
            if (cancelled) proc.destroy()
            proc.waitFor()
        } catch (_: Exception) {
        }
        return agg.dirSet.isNotEmpty() || agg.fileCountTotal > 0
    }

    private fun scanWithShizuku(scanRoot: String, agg: Aggregator): Boolean {
        val exec = shizukuExecutor ?: return false
        val cmd = findCommand(scanRoot)
        val stream = try {
            exec(cmd)
        } catch (e: Exception) {
            null
        } ?: return false

        try {
            stream.use { input ->
                activeStream = input
                readFindStream(input, scanRoot, agg)
            }
        } catch (e: Exception) {
            // gunakan data parsial
        }
        return agg.dirSet.isNotEmpty() || agg.fileCountTotal > 0
    }

    private fun handleRecord(rec: java.io.ByteArrayOutputStream, agg: Aggregator) {
        if (rec.size() < 3) return
        val line = rec.toByteArray().toString(Charsets.UTF_8)
        agg.feed(line)
    }

    /* ─────────────────────── scan lewat java.io.File ───────────────────── */

    private fun scanWithJava(scanRoot: String, agg: Aggregator): Boolean {
        val rootFile = File(scanRoot)
        if (!rootFile.exists()) return false

        if (rootFile.isFile) {
            agg.feedFile(rootFile.absolutePath, rootFile.length())
            return true
        }
        if (!rootFile.isDirectory) return false

        val stack = ArrayDeque<File>()
        stack.add(rootFile)
        agg.feedDir(rootFile.absolutePath)

        var dirsScanned = 0L
        while (!cancelled) {
            val dir = stack.poll() ?: break
            val children = try {
                dir.listFiles()
            } catch (e: Exception) {
                null
            }
            if (children == null) {
                agg.skippedDirs++
                continue
            }
            for (child in children) {
                val childPath = child.absolutePath
                try {
                    if (child.isDirectory && !isSymlink(child)) {
                        agg.feedDir(childPath)
                        stack.add(child)
                    } else {
                        agg.feedFile(childPath, if (child.isFile || !child.isDirectory) child.length() else 0L)
                    }
                } catch (e: Exception) {
                    agg.skippedDirs++
                }
            }
            dirsScanned++
            if ((dirsScanned and 0x1F) == 0L) {
                onProgress(agg.fileCountTotal, agg.dirSize[scanRoot] ?: 0L)
            }
        }
        return true
    }

    private fun isSymlink(file: File): Boolean = try {
        Files.isSymbolicLink(file.toPath())
    } catch (e: Exception) {
        false
    }

    /* ───────────────────────── finalisasi pohon ────────────────────────── */

    private fun finish(agg: Aggregator, scanRoot: String, mode: ScanMode, startMs: Long): ScanResult {
        agg.dirSet.add(scanRoot)

        // Buat node folder — urut dari path terpendek agar parent selalu ada lebih dulu.
        val ascending = agg.dirSet.sortedBy { it.length }
        val nodes = HashMap<String, Node>(agg.dirSet.size + 64)
        var rootNode: Node? = null
        for (p in ascending) {
            if (p == scanRoot) {
                val n = Node(p, null, true)
                nodes[p] = n
                rootNode = n
                continue
            }
            val parentPath = Node.parentOf(p) ?: continue
            val parent = nodes[parentPath] ?: continue // di luar scanRoot
            val n = Node(p, parent, true)
            nodes[p] = n
            parent.children.add(n)
            parent.dirCount += 1
        }

        val root = rootNode ?: return ScanResult(
            root = null, rootUnavailable = false, pathMissing = true,
            totalBytes = 0, totalFiles = 0, totalDirs = 0,
            skippedDirs = agg.skippedDirs, durationMs = 0,
            usedRoot = mode == ScanMode.ROOT, usedShizuku = mode == ScanMode.SHIZUKU,
            cancelled = false
        )

        // Ukuran subtree + jumlah file langsung per folder.
        for (n in nodes.values) {
            n.size = agg.dirSize[n.path] ?: 0L
            n.fileCount = agg.directFileCount[n.path] ?: 0L
        }

        // File: 8 terbesar per folder disimpan, sisanya node sintetis.
        for ((dir, files) in agg.topFiles) {
            val parent = nodes[dir] ?: continue
            var keptBytes = 0L
            var kept = 0
            for ((fullPath, size) in files) {
                val fn = Node(fullPath, parent, false)
                fn.size = size
                parent.children.add(fn)
                keptBytes += size
                kept++
            }
            val directBytes = agg.directFileBytes[dir] ?: 0L
            val restBytes = (directBytes - keptBytes).coerceAtLeast(0L)
            val restCount = (agg.directFileCount[dir] ?: 0L) - kept
            if (restCount > 0) {
                val syn = Node(
                    parent.path + "/.filelight-others",
                    parent,
                    false,
                    nameOverride = "${SizeUtil.formatCount(restCount)} file lainnya"
                )
                syn.size = restBytes
                syn.fileCount = restCount
                syn.synthetic = true
                parent.children.add(syn)
            }
        }

        // Urutkan semua anak dari terbesar ke terkecil (gaya Filelight).
        for (n in nodes.values) {
            if (n.children.size > 1) n.children.sortByDescending { it.size }
        }

        return ScanResult(
            root = root,
            rootUnavailable = false,
            pathMissing = false,
            totalBytes = root.size,
            totalFiles = agg.fileCountTotal,
            totalDirs = (agg.dirSet.size - 1).coerceAtLeast(0).toLong(),
            skippedDirs = agg.skippedDirs,
            durationMs = System.currentTimeMillis() - startMs,
            usedRoot = mode == ScanMode.ROOT,
            usedShizuku = mode == ScanMode.SHIZUKU,
            cancelled = false
        )
    }

    private fun cancelledResult(agg: Aggregator, mode: ScanMode, startMs: Long) = ScanResult(
        root = null, rootUnavailable = false, pathMissing = false,
        totalBytes = 0, totalFiles = 0, totalDirs = 0,
        skippedDirs = agg.skippedDirs, durationMs = System.currentTimeMillis() - startMs,
        usedRoot = mode == ScanMode.ROOT, usedShizuku = mode == ScanMode.SHIZUKU, cancelled = true
    )

    /* ─────────────────── agregasi streaming dari output find ───────────── */

    private class Aggregator(private val scanRoot: String) {
        val dirSize = HashMap<String, Long>()
        val directFileBytes = HashMap<String, Long>()
        val directFileCount = HashMap<String, Long>()
        val dirSet = HashSet<String>()
        val topFiles = HashMap<String, ArrayList<Pair<String, Long>>>()

        var fileCountTotal = 0L
        var skippedDirs = 0

        private val FS = '\u0001'
        private val scanRootBase = if (scanRoot == "/") "" else scanRoot

        fun feed(line: String) {
            if (line.length < 4) return
            val marker = line[0]
            if (marker != 'd' && marker != 'f') return
            if (line[1] != FS) return
            val sizeEnd = line.indexOf(FS, 2)
            if (sizeEnd < 0) return
            val size = line.substring(2, sizeEnd).toLongOrNull() ?: return
            val full = line.substring(sizeEnd + 1)
            if (full.isEmpty()) return
            if (marker == 'd') feedDir(full) else feedFile(full, size)
        }

        private fun inRoot(p: String): Boolean =
            if (scanRoot == "/") p.startsWith("/") else (p == scanRoot || p.startsWith("$scanRootBase/"))

        fun feedDir(full: String) {
            val d = Node.normalize(full)
            if (d.isEmpty() || !inRoot(d)) return
            dirSet.add(d)
        }

        fun feedFile(full: String, size: Long) {
            val parent = Node.parentOf(full) ?: return
            if (!inRoot(parent)) return

            fileCountTotal++
            directFileBytes.merge(parent, size, Long::plus)
            directFileCount.merge(parent, 1L, Long::plus)

            // File terbesar per folder (maks 8, min-heap sederhana).
            val list = topFiles.getOrPut(parent) { ArrayList(8) }
            if (list.size < 8) {
                list.add(full to size)
            } else {
                var minIdx = 0
                var minVal = list[0].second
                for (i in 1 until list.size) {
                    if (list[i].second < minVal) {
                        minVal = list[i].second
                        minIdx = i
                    }
                }
                if (size > minVal) list[minIdx] = full to size
            }

            // Akumulasi ukuran ke seluruh leluhur di dalam scanRoot.
            var cur: String? = parent
            while (cur != null && inRoot(cur)) {
                dirSize.merge(cur, size, Long::plus)
                if (cur == scanRoot) break
                cur = Node.parentOf(cur)
            }
        }
    }
}
