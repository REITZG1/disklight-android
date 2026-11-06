package org.filelight.android

import android.os.ParcelFileDescriptor
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArraySet

/**
 * UserService Shizuku: class ini diinstansiasi langsung oleh Shizuku
 * starter (app_process) sebagai IBinder — BUKAN via Service Android.
 * Karenanya class ini mewarisi Stub AIDL (extends Binder) dan tidak
 * boleh extends Service.
 *
 * Mengekspos operasi shell (`sh -c <cmd>`) dan mengalirkan stdout+stderr
 * lewat ParcelFileDescriptor pipe. Berjalan sebagai uid starter Shizuku
 * (shell 2000 via ADB, atau 0 bila starter berjalan sebagai root).
 */
class ShizukuShellService() : IShizukuShell.Stub() {

    private val activeProcesses = CopyOnWriteArraySet<Process>()

    override fun runCommand(cmd: String): ParcelFileDescriptor {
        val proc = try {
            ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            val pipes = ParcelFileDescriptor.createPipe()
            try {
                FileOutputStream(pipes[1].fileDescriptor).use { it.write("ERR:${e.message}".toByteArray()) }
            } catch (_: Exception) {
            }
            try { pipes[1].close() } catch (_: Exception) {}
            return pipes[0]
        }

        activeProcesses.add(proc)
        val pipes = ParcelFileDescriptor.createPipe()
        val readEnd = pipes[0]
        val writeEnd = pipes[1]

        Thread {
            try {
                copy(proc.inputStream, writeEnd.fileDescriptor)
            } catch (_: Exception) {
            } finally {
                try { writeEnd.close() } catch (_: Exception) {}
                activeProcesses.remove(proc)
                try { proc.waitFor() } catch (_: Exception) {}
            }
        }.apply { isDaemon = true; start() }

        return readEnd
    }

    override fun getUidInfo(): String {
        return try {
            val p = ProcessBuilder("id").redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            p.waitFor()
            out
        } catch (e: Exception) {
            "uid=unknown (${e.message})"
        }
    }

    private fun copy(input: InputStream, fd: FileDescriptor) {
        val out = FileOutputStream(fd)
        val buf = ByteArray(1 shl 14)
        try {
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                out.flush()
            }
        } finally {
            try { out.flush() } catch (_: Exception) {}
        }
    }
}
