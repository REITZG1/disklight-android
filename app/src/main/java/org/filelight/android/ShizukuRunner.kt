package org.filelight.android

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import rikka.shizuku.Shizuku
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Bridge untuk Shizuku:
 * - Deteksi keberadaan aplikasi Shizuku, apakah sedang berjalan, izin.
 * - Bind ke [ShizukuShellService] sebagai UserService Shizuku (uid shell/root starter).
 * - Menjalankan perintah shell lewat binder dan mengembalikan InputStream stdout.
 *
 * Dipakai dari background thread (scan/delete). Bind dilakukan sinkron dengan latch.
 */
object ShizukuRunner {

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val REQUEST_CODE = 1001

    @Volatile
    private var shell: IShizukuShell? = null

    @Volatile
    private var bindLatch: CountDownLatch? = null

    private var connection: ServiceConnection? = null

    fun isInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    /** true bila server Shizuku sedang berjalan & binder terhubung. */
    fun isRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    /** 0 = granted, nonzero = belum. -1 bila tidak bisa di-bind. */
    fun permissionState(): Int {
        return try {
            if (!isRunning()) return -1
            Shizuku.checkSelfPermission()
        } catch (e: Exception) {
            -1
        }
    }

    fun hasPermission(): Boolean = permissionState() == PackageManager.PERMISSION_GRANTED

    fun requestPermission(activity: Activity) {
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Exception) {
            // abaikan - listener akan melaporkan kegagalan
        }
    }

    fun getUid(): Int {
        return try {
            if (!isRunning()) -1 else Shizuku.getUid()
        } catch (e: Exception) {
            -1
        }
    }

    fun getSelinuxContext(): String {
        return try {
            Shizuku.getSELinuxContext() ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    @Synchronized
    private fun ensureBound(timeoutMs: Long = 15000): Boolean {
        if (shell != null) return true
        val latch = CountDownLatch(1)
        bindLatch = latch

        val args = Shizuku.UserServiceArgs(
            ComponentName(
                "org.filelight.android",
                ShizukuShellService::class.java.name
            )
        ).processNameSuffix("shizuku")

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                shell = try {
                    IShizukuShell.Stub.asInterface(binder)
                } catch (e: Exception) {
                    null
                }
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                shell = null
            }
        }
        connection = conn

        try {
            Shizuku.bindUserService(args, conn)
            val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            return ok && shell != null
        } catch (e: Exception) {
            return false
        } finally {
            bindLatch = null
        }
    }

    @Synchronized
    fun unbind() {
        val conn = connection ?: return
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(
                    "org.filelight.android",
                    ShizukuShellService::class.java.name
                )
            ).processNameSuffix("shizuku")
            Shizuku.unbindUserService(args, conn, false)
        } catch (e: Exception) {
        }
        connection = null
        shell = null
    }

    /**
     * Jalankan perintah shell via Shizuku, kembalikan stream stdout+stderr.
     * Mengembalikan null bila Shizuku tidak terikat / izin belum diberikan.
     * Panggil dari background thread. Tutup stream setelah selesai.
     */
    fun runCommandStream(cmd: String): InputStream? {
        if (!ensureBound()) return null
        val s = shell ?: return null
        return try {
            val pfd: ParcelFileDescriptor = s.runCommand(cmd)
            // PENTING: tahan referensi pfd selama stream hidup.
            // ParcelFileDescriptor punya finalizer yang menutup fd-nya saat
            // di-GC — bila kita hanya menyimpan FileDescriptor-nya, fd bisa
            // tertutup lebih awal sehingga output besar terpotong ~250 KB.
            val raw = java.io.FileInputStream(pfd.fileDescriptor)
            object : java.io.FilterInputStream(raw) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        try { pfd.close() } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Jalankan perintah dan baca seluruh output sebagai String (untuk output kecil). */
    fun runCommandText(cmd: String): String? {
        val stream = runCommandStream(cmd) ?: return null
        return try {
            stream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            null
        }
    }

    /** Info uid dari sisi service (mis. uid=2000(shell) ...). */
    fun uidInfo(): String {
        if (!ensureBound()) return "uid=unknown (shizuku not bound)"
        return try {
            shell?.getUidInfo() ?: "uid=unknown"
        } catch (e: Exception) {
            "uid=unknown (${e.message})"
        }
    }
}
