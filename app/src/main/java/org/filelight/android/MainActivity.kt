package org.filelight.android

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var sunburst: SunburstView
    private lateinit var childList: ListView
    private lateinit var pathInput: EditText
    private lateinit var scanBtn: android.widget.Button
    private lateinit var upBtn: ImageButton
    private lateinit var modeNormalBtn: android.widget.Button
    private lateinit var modeShizukuBtn: android.widget.Button
    private lateinit var modeRootBtn: android.widget.Button
    private lateinit var rootDot: View
    private lateinit var rootLabel: TextView
    private lateinit var rootState: TextView
    private lateinit var statsBar: TextView
    private lateinit var currentInfo: TextView
    private lateinit var storageInfo: TextView
    private lateinit var adapter: ChildAdapter

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    private var scanRoot: Node? = null
    private var currentNode: Node? = null
    private var scanner: DiskScanner? = null
    private var scanDialog: Dialog? = null
    private var scanning = false
    private var deleting = false
    private var scanPathUsed = "/sdcard"
    private var currentMode: ScanMode = ScanMode.ROOT
    private var shizukuUidInfo: String? = null
    private var pendingScanPath: String? = null

    private val dangerousPaths = setOf("/", "/data", "/system")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("filelight", MODE_PRIVATE)

        sunburst = findViewById(R.id.sunburst)
        childList = findViewById(R.id.childList)
        pathInput = findViewById(R.id.pathInput)
        scanBtn = findViewById(R.id.scanBtn)
        upBtn = findViewById(R.id.upBtn)
        modeNormalBtn = findViewById(R.id.modeNormalBtn)
        modeShizukuBtn = findViewById(R.id.modeShizukuBtn)
        modeRootBtn = findViewById(R.id.modeRootBtn)
        rootDot = findViewById(R.id.rootDot)
        rootLabel = findViewById(R.id.rootLabel)
        rootState = findViewById(R.id.rootState)
        statsBar = findViewById(R.id.statsBar)
        currentInfo = findViewById(R.id.currentInfo)
        storageInfo = findViewById(R.id.storageInfo)

        adapter = ChildAdapter(this)
        childList.adapter = adapter
        childList.emptyView = findViewById(R.id.emptyView)

        // Migrasi preferensi lama rootEnabled -> scanMode
        if (!prefs.contains("scanMode") && prefs.contains("rootEnabled")) {
            val legacy = if (prefs.getBoolean("rootEnabled", true)) "ROOT" else "NORMAL"
            prefs.edit().putString("scanMode", legacy).apply()
        }
        currentMode = ScanMode.fromString(prefs.getString("scanMode", "ROOT"))

        val firstRun = !prefs.contains("initialized")
        if (firstRun) {
            pathInput.setText("/sdcard")
            currentMode = ScanMode.ROOT
            prefs.edit().putString("scanMode", "ROOT").putBoolean("initialized", true).apply()
        } else {
            pathInput.setText(prefs.getString("lastPath", "/sdcard"))
        }

        // Listener hasil izin Shizuku
        Shizuku.addRequestPermissionResultListener { _, grantResult ->
            mainHandler.post {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    updateModeState()
                    val p = pendingScanPath
                    if (p != null && currentMode == ScanMode.SHIZUKU) {
                        pendingScanPath = null
                        startScan(p, ScanMode.SHIZUKU)
                    } else if (p != null) {
                        pendingScanPath = null
                    }
                } else {
                    Toast.makeText(this, "Izin Shizuku ditolak", Toast.LENGTH_LONG).show()
                    pendingScanPath = null
                    updateModeState()
                }
            }
        }
        Shizuku.addBinderReceivedListener { updateModeState() }
        Shizuku.addBinderDeadListener { updateModeState() }

        // ── Wiring ─────────────────────────────────────────────
        scanBtn.setOnClickListener {
            val path = pathInput.text.toString().trim().ifEmpty { "/sdcard" }
            startScanWithChecks(path, currentMode)
        }
        pathInput.setOnEditorActionListener { _, _, _ ->
            val path = pathInput.text.toString().trim().ifEmpty { "/sdcard" }
            startScanWithChecks(path, currentMode)
            true
        }
        upBtn.setOnClickListener { navigateUp() }

        modeNormalBtn.setOnClickListener { setMode(ScanMode.NORMAL) }
        modeShizukuBtn.setOnClickListener { setMode(ScanMode.SHIZUKU) }
        modeRootBtn.setOnClickListener { setMode(ScanMode.ROOT) }

        findViewById<android.widget.Button>(R.id.chipRoot).setOnClickListener { presetScan("/") }
        findViewById<android.widget.Button>(R.id.chipData).setOnClickListener { presetScan("/data") }
        findViewById<android.widget.Button>(R.id.chipSystem).setOnClickListener { presetScan("/system") }
        findViewById<android.widget.Button>(R.id.chipSdcard).setOnClickListener { presetScan("/sdcard") }
        findViewById<android.widget.Button>(R.id.chipInternal).setOnClickListener { presetScan("/storage/emulated/0") }
        findViewById<android.widget.Button>(R.id.chipAndroid).setOnClickListener { presetScan("/data/media") }

        findViewById<ImageButton>(R.id.aboutBtn).setOnClickListener { showAbout() }

        sunburst.onNavigateUp = { navigateUp() }
        sunburst.onNavigateInto = { node -> navigateTo(node) }
        sunburst.onSelect = { node ->
            Toast.makeText(this, "${node.name} — ${SizeUtil.format(node.size)}", Toast.LENGTH_SHORT).show()
        }
        sunburst.onLongSelect = { node -> showItemActions(node) }

        childList.setOnItemClickListener { _, _, position, _ ->
            val node = adapter.getItem(position)
            if (node.isDirectory && node.children.isNotEmpty()) {
                navigateTo(node)
            } else if (node.isDirectory) {
                Toast.makeText(this, "Folder kosong", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "${node.name} — ${SizeUtil.format(node.size)}", Toast.LENGTH_SHORT).show()
            }
        }
        childList.setOnItemLongClickListener { _, _, position, _ ->
            showItemActions(adapter.getItem(position))
            true
        }

        // ── Initial state ─────────────────────────────────────
        updateUpButton()
        updateModeButtons()
        updateModeState()
        updateStorageInfo(pathInput.text.toString().trim().ifEmpty { "/sdcard" })

        if (Build.VERSION.SDK_INT <= 32) {
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 100)
        }

        if (firstRun) {
            // Berurutan agar dialog tidak bertumpuk: hint storage dulu (bila perlu),
            // baru cek ketersediaan root, lalu auto-scan /sdcard.
            showStorageAccessHintIfNeeded {
                executor.execute {
                    val ok = Su.isRootAvailable()
                    mainHandler.post {
                        currentMode = if (ok) ScanMode.ROOT else ScanMode.NORMAL
                        prefs.edit().putString("scanMode", currentMode.name).apply()
                        updateModeButtons()
                        updateModeState()
                        startScan("/sdcard", currentMode)
                    }
                }
            }
        }
    }

    /* ═══════════════════════════ mode ══════════════════════════════════ */

    private fun setMode(mode: ScanMode) {
        if (mode == currentMode) {
            updateModeState()
            return
        }
        // Untuk Shizuku, pastikan izin sebelum pindah (minta izin bila perlu)
        if (mode == ScanMode.SHIZUKU) {
            if (!ShizukuRunner.isInstalled(this)) {
                AlertDialog.Builder(this, R.style.Filelight_Dialog)
                    .setTitle("Shizuku tidak terpasang")
                    .setMessage(
                        "Pasang aplikasi Shizuku (moe.shizuku.privileged.api), jalankan sampai statusnya \"running\", " +
                            "lalu pilih Shizuku lagi.\n\n" +
                            "Di perangkat tanpa root, Shizuku di-start via ADB; di perangkat root, langsung dari aplikasi Shizuku."
                    )
                    .setPositiveButton("OK", null)
                    .show()
                return
            }
            if (!ShizukuRunner.isRunning()) {
                AlertDialog.Builder(this, R.style.Filelight_Dialog)
                    .setTitle("Shizuku belum berjalan")
                    .setMessage(
                        "Buka aplikasi Shizuku dan tekan Start (routed " +
                            "atau via ADB/wireless debugging), tunggu statusnya \"running\", lalu coba lagi."
                    )
                    .setPositiveButton("OK", null)
                    .show()
                return
            }
            if (!ShizukuRunner.hasPermission()) {
                ShizukuRunner.requestPermission(this)
                Toast.makeText(this, "Menunggu izin Shizuku…", Toast.LENGTH_LONG).show()
                // Tetap pindah agar saat izin diberikan kita langsung bisa pindai
                currentMode = mode
                prefs.edit().putString("scanMode", mode.name).apply()
                updateModeButtons()
                updateModeState()
                return
            }
        }
        currentMode = mode
        prefs.edit().putString("scanMode", mode.name).apply()
        updateModeButtons()
        updateModeState()
        Toast.makeText(this, "Mode: ${mode.label}", Toast.LENGTH_SHORT).show()
    }

    private fun updateModeButtons() {
        val sel = R.drawable.bg_button
        val unsel = R.drawable.bg_chip
        modeNormalBtn.setBackgroundResource(if (currentMode == ScanMode.NORMAL) sel else unsel)
        modeShizukuBtn.setBackgroundResource(if (currentMode == ScanMode.SHIZUKU) sel else unsel)
        modeRootBtn.setBackgroundResource(if (currentMode == ScanMode.ROOT) sel else unsel)

        val selColor = getColor(R.color.breeze_accent)
        val unselColor = getColor(R.color.breeze_text)
        modeNormalBtn.setTextColor(if (currentMode == ScanMode.NORMAL) getColor(R.color.breeze_bg) else unselColor)
        modeShizukuBtn.setTextColor(if (currentMode == ScanMode.SHIZUKU) getColor(R.color.breeze_bg) else unselColor)
        modeRootBtn.setTextColor(if (currentMode == ScanMode.ROOT) getColor(R.color.breeze_bg) else unselColor)
        rootLabel.text = currentMode.label.uppercase()
    }

    /* ══════════════════════════ scanning ═════════════════════════════════ */

    private fun presetScan(path: String) {
        pathInput.setText(path)
        startScanWithChecks(path, currentMode)
    }

    private fun startScanWithChecks(path: String, mode: ScanMode) {
        if (scanning || deleting) return
        val normalized = Node.normalize(path)
        if (normalized in dangerousPaths) {
            AlertDialog.Builder(this, R.style.Filelight_Dialog)
                .setTitle("Pindai $normalized?")
                .setMessage("Memindai $normalized dapat memakan waktu lama dan membaca data pribadi. Lanjutkan?")
                .setPositiveButton("Pindai") { _, _ -> ensureAndScan(normalized, mode) }
                .setNegativeButton("Batal", null)
                .show()
            return
        }
        ensureAndScan(normalized, mode)
    }

    /** Untuk Shizuku: pastikan izin dulu (tunda scan sampai izin diberikan). */
    private fun ensureAndScan(path: String, mode: ScanMode) {
        if (mode == ScanMode.SHIZUKU) {
            if (!ShizukuRunner.isInstalled(this)) {
                AlertDialog.Builder(this, R.style.Filelight_Dialog)
                    .setTitle("Shizuku tidak terpasang")
                    .setMessage("Pasang aplikasi Shizuku dulu, lalu pindai lagi.")
                    .setPositiveButton("OK", null)
                    .show()
                return
            }
            if (!ShizukuRunner.isRunning()) {
                AlertDialog.Builder(this, R.style.Filelight_Dialog)
                    .setTitle("Shizuku belum berjalan")
                    .setMessage("Buka aplikasi Shizuku dan tekan Start sampai statusnya \"running\".")
                    .setPositiveButton("OK", null)
                    .show()
                return
            }
            if (!ShizukuRunner.hasPermission()) {
                pendingScanPath = path
                ShizukuRunner.requestPermission(this)
                Toast.makeText(this, "Menunggu izin Shizuku…", Toast.LENGTH_LONG).show()
                return
            }
        }
        startScan(path, mode)
    }

    private fun startScan(path: String, mode: ScanMode) {
        if (scanning || deleting) return
        scanning = true
        scanPathUsed = path
        scanBtn.isEnabled = false
        prefs.edit().putString("lastPath", path).putString("scanMode", mode.name).apply()
        updateStorageInfo(path)
        showScanDialog(path, mode)

        val shizukuExec: ((String) -> java.io.InputStream?)? =
            if (mode == ScanMode.SHIZUKU) {
                { cmd -> ShizukuRunner.runCommandStream(cmd) }
            } else null

        val scan = DiskScanner(path, mode, onProgress = { files, bytes ->
            mainHandler.post {
                scanDialog?.findViewById<TextView>(R.id.scanProgress)?.let { tv ->
                    tv.text = "${SizeUtil.formatCount(files)} file · ${SizeUtil.format(bytes)}"
                }
            }
        }, shizukuExecutor = shizukuExec)
        scanner = scan

        executor.execute {
            val result = try {
                scan.scan()
            } catch (e: Exception) {
                ScanResult(
                    root = null, rootUnavailable = false, shizukuUnavailable = mode == ScanMode.SHIZUKU,
                    pathMissing = false,
                    totalBytes = 0, totalFiles = 0, totalDirs = 0,
                    skippedDirs = 0, durationMs = 0, usedRoot = mode == ScanMode.ROOT,
                    usedShizuku = mode == ScanMode.SHIZUKU, cancelled = false
                )
            }
            mainHandler.post { onScanFinished(result) }
        }
    }

    private fun showScanDialog(path: String, mode: ScanMode) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_scan, null)
        view.findViewById<TextView>(R.id.scanTarget).text = when (mode) {
            ScanMode.ROOT -> "$path  (root)"
            ScanMode.SHIZUKU -> "$path  (shizuku/shell)"
            else -> path
        }
        view.findViewById<TextView>(R.id.scanProgress).text = "0 file · 0 B"
        view.findViewById<android.widget.Button>(R.id.scanCancel).setOnClickListener {
            scanner?.cancel()
            dismissScanDialog()
            Toast.makeText(this, "Pindai dibatalkan", Toast.LENGTH_SHORT).show()
        }
        scanDialog = AlertDialog.Builder(this, R.style.Filelight_Dialog)
            .setView(view)
            .setCancelable(false)
            .create()
        scanDialog?.show()
    }

    private fun dismissScanDialog() {
        try {
            scanDialog?.dismiss()
        } catch (_: Exception) {
        }
        scanDialog = null
    }

    private fun onScanFinished(result: ScanResult) {
        scanning = false
        scanBtn.isEnabled = true
        dismissScanDialog()

        when {
            result.cancelled -> return

            result.rootUnavailable -> {
                AlertDialog.Builder(this, R.style.Filelight_Dialog)
                    .setTitle("Root tidak tersedia")
                    .setMessage(
                        "Perangkat ini tidak memiliki akses root atau perintah 'su' tidak terpasang.\n\n" +
                            "Memindai $scanPathUsed tanpa root? Data di dalam /data milik aplikasi lain tidak akan terbaca."
                    )
                    .setPositiveButton("Tanpa Root") { _, _ -> startScan(scanPathUsed, ScanMode.NORMAL) }
                    .setNegativeButton("Batal", null)
                    .show()
                return
            }

            result.shizukuUnavailable -> {
                AlertDialog.Builder(this, R.style.Filelight_Dialog)
                    .setTitle("Shizuku tidak tersedia")
                    .setMessage(
                        "Shizuku belum terpasang, belum berjalan, atau belum diberikan izin ke DiskLight.\n\n" +
                            "Buka aplikasi Shizuku, tekan Start sampai statusnya \"running\", lalu izinkan DiskLight di Shizuku.\n\n" +
                            "Mau memindai $scanPathUsed tanpa Shizuku?"
                    )
                    .setPositiveButton("Tanpa Shizuku") { _, _ -> startScan(scanPathUsed, ScanMode.NORMAL) }
                    .setNegativeButton("Batal", null)
                    .show()
                return
            }

            result.pathMissing || result.root == null -> {
                Toast.makeText(this, "Path tidak ada atau tidak dapat diakses: $scanPathUsed", Toast.LENGTH_LONG).show()
                return
            }
        }

        val root = result.root!!
        // Tampilkan alias path yang diketik pengguna (mis. "/sdcard") di tengah grafik
        root.displayName = scanPathUsed
        scanRoot = root
        navigateTo(root)

        val skippedNote = if (result.skippedDirs > 0) " · ${result.skippedDirs} folder terkunci" else ""
        statsBar.text = "${SizeUtil.formatCount(result.totalDirs)} folder · " +
            "${SizeUtil.formatCount(result.totalFiles)} file · " +
            "${SizeUtil.format(result.totalBytes)} · " +
            SizeUtil.formatDuration(result.durationMs) + skippedNote

        when {
            result.usedRoot -> {
                rootState.text = "ROOT AKTIF"
                rootState.setTextColor(getColor(R.color.breeze_ok))
            }
            result.usedShizuku -> {
                val uid = ShizukuRunner.getUid()
                rootState.text = if (uid >= 0) "SHIZUKU AKTIF (uid $uid)" else "SHIZUKU AKTIF"
                rootState.setTextColor(getColor(R.color.breeze_accent))
            }
            else -> {
                rootState.text = "NORMAL"
                rootState.setTextColor(getColor(R.color.breeze_text_dim))
            }
        }

        if (!result.usedRoot && !result.usedShizuku && result.skippedDirs > 3) {
            Toast.makeText(
                this,
                "Banyak folder terkunci. Aktifkan Root atau Shizuku untuk memindai lebih luas.",
                Toast.LENGTH_LONG
            ).show()
        }
        if (result.totalFiles == 0L && result.totalDirs == 0L) {
            Toast.makeText(
                this,
                "Tidak ada data. Periksa path atau izin penyimpanan.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /* ═══════════════════════════ navigasi ════════════════════════════════ */

    private fun navigateTo(node: Node) {
        currentNode = node
        sunburst.centerNode = node
        currentInfo.text = "${SizeUtil.shortPath(node.displayName ?: node.path)}  ·  ${SizeUtil.format(node.size)}"
        adapter.setData(node.children, node.size)
        updateUpButton()
    }

    private fun navigateUp() {
        val cur = currentNode ?: return
        val parent = cur.parent ?: return
        navigateTo(parent)
    }

    private fun updateUpButton() {
        upBtn.alpha = if (currentNode?.parent != null) 1f else 0.35f
        upBtn.isEnabled = currentNode?.parent != null
    }

    override fun onBackPressed() {
        if (scanning) {
            scanner?.cancel()
            return
        }
        val cur = currentNode
        if (cur != null && cur.parent != null) {
            navigateUp()
            return
        }
        super.onBackPressed()
    }

    /* ═════════════════════════════ misc ══════════════════════════════════ */

    private fun updateModeState() {
        when (currentMode) {
            ScanMode.NORMAL -> {
                rootDot.background?.setTint(getColor(R.color.breeze_text_dim))
                rootState.text = "NORMAL"
                rootState.setTextColor(getColor(R.color.breeze_text_dim))
            }
            ScanMode.ROOT -> {
                rootDot.background?.setTint(getColor(R.color.breeze_warning))
                rootState.text = "CEK ROOT…"
                rootState.setTextColor(getColor(R.color.breeze_warning))
                executor.execute {
                    val ok = Su.isRootAvailable()
                    mainHandler.post {
                        rootDot.background?.setTint(
                            getColor(if (ok) R.color.breeze_ok else R.color.breeze_danger)
                        )
                        if (currentMode == ScanMode.ROOT) {
                            rootState.text = if (ok) "ROOT AKTIF" else "ROOT TIDAK TERSEDIA"
                            rootState.setTextColor(
                                getColor(if (ok) R.color.breeze_ok else R.color.breeze_danger)
                            )
                        }
                    }
                }
            }
            ScanMode.SHIZUKU -> {
                val installed = ShizukuRunner.isInstalled(this)
                val running = installed && ShizukuRunner.isRunning()
                val granted = running && ShizukuRunner.hasPermission()
                val uid = if (granted) ShizukuRunner.getUid() else -1
                rootDot.background?.setTint(
                    getColor(
                        when {
                            !installed -> R.color.breeze_danger
                            !running -> R.color.breeze_warning
                            !granted -> R.color.breeze_warning
                            else -> R.color.breeze_ok
                        }
                    )
                )
                rootState.text = when {
                    !installed -> "SHIZUKU TIDAK TERPASANG"
                    !running -> "SHIZUKU BELUM JALAN"
                    !granted -> "SHIZUKU: MENUNGGU IZIN"
                    uid >= 0 -> "SHIZUKU AKTIF (uid $uid)"
                    else -> "SHIZUKU AKTIF"
                }
                rootState.setTextColor(
                    getColor(
                        when {
                            !installed -> R.color.breeze_danger
                            granted -> R.color.breeze_accent
                            else -> R.color.breeze_warning
                        }
                    )
                )
                // Ambil info uid lengkap dari UserService (mis. uid=2000(shell)...)
                if (granted) {
                    executor.execute {
                        val info = try { ShizukuRunner.uidInfo() } catch (e: Exception) { null }
                        if (!info.isNullOrEmpty()) shizukuUidInfo = info
                    }
                }
            }
        }
        rootLabel.text = currentMode.label.uppercase()
    }

    private fun updateStorageInfo(path: String) {
        try {
            val fs = StatFs(path)
            val total = fs.totalBytes
            val avail = fs.availableBytes
            val used = total - avail
            storageInfo.text =
                "${SizeUtil.format(used)} terpakai · ${SizeUtil.format(avail)} tersedia · ${SizeUtil.format(total)} total"
        } catch (e: Exception) {
            storageInfo.text = ""
        }
    }

    /* ═══════════════════════════ hapus ═════════════════════════════════ */

    /** Dialog aksi saat item ditekan-tahan: info + opsi hapus. */
    private fun showItemActions(node: Node) {
        if (scanning || deleting) {
            Toast.makeText(this, "Tunggu operasi berjalan selesai", Toast.LENGTH_SHORT).show()
            return
        }
        if (node.parent == null) {
            Toast.makeText(this, "Folder root pemindaian tidak bisa dihapus", Toast.LENGTH_SHORT).show()
            return
        }
        val detail = buildString {
            append(node.path)
            append("\n\nUkuran: ").append(SizeUtil.format(node.size))
            if (node.isDirectory) {
                append("\nLangsung: ").append(SizeUtil.formatCount(node.fileCount)).append(" file · ")
                    .append(SizeUtil.formatCount(node.dirCount)).append(" folder")
            }
            if (node.synthetic) {
                append("\n\nKelompok file kecil yang digabung. Menghapusnya berarti menghapus semua file di dalamnya.")
            }
        }
        AlertDialog.Builder(this, R.style.Filelight_Dialog)
            .setTitle(if (node.isDirectory) "Folder" else "File")
            .setMessage(detail)
            .setPositiveButton("Hapus") { _, _ -> confirmDelete(node) }
            .setNegativeButton("Batal", null)
            .show()
    }

    /** Konfirmasi kuat sebelum menghapus — wajib centang pengakuan. */
    private fun confirmDelete(node: Node) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_confirm_delete, null)
        view.findViewById<TextView>(R.id.deletePath).text = node.path
        view.findViewById<TextView>(R.id.deleteInfo).text = buildString {
            append("Ukuran: ").append(SizeUtil.format(node.size))
            if (node.isDirectory) {
                append(" · langsung ").append(SizeUtil.formatCount(node.fileCount)).append(" file")
            }
        }
        val ack = view.findViewById<android.widget.CheckBox>(R.id.deleteAck)
        val confirmBtn = view.findViewById<android.widget.Button>(R.id.deleteConfirm)
        ack.setOnCheckedChangeListener { _, checked ->
            confirmBtn.isEnabled = checked
            confirmBtn.alpha = if (checked) 1f else 0.45f
        }
        confirmBtn.alpha = 0.45f

        val dialog = AlertDialog.Builder(this, R.style.Filelight_Dialog)
            .setView(view)
            .setCancelable(true)
            .create()
        view.findViewById<android.widget.Button>(R.id.deleteCancel).setOnClickListener { dialog.dismiss() }
        confirmBtn.setOnClickListener {
            dialog.dismiss()
            startDelete(node)
        }
        dialog.show()
    }

    private fun startDelete(node: Node) {
        if (deleting) return
        deleting = true

        val progress = AlertDialog.Builder(this, R.style.Filelight_Dialog)
            .setTitle("Menghapus…")
            .setMessage(node.path)
            .setCancelable(false)
            .create()
        progress.show()

        executor.execute {
            val ok = deleteNodeBlocking(node)
            mainHandler.post {
                try {
                    progress.dismiss()
                } catch (_: Exception) {
                }
                deleting = false
                if (ok) {
                    onDeleteSuccess(node)
                } else {
                    Toast.makeText(
                        this,
                        "Gagal menghapus. Periksa izin atau apakah berkas sedang dipakai.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun deleteNodeBlocking(node: Node): Boolean {
        val path = node.path
        val escaped = Su.escape(path)
        val mode = currentMode

        return when (mode) {
            ScanMode.ROOT -> {
                try {
                    // rm -rf tidak mengikuti symlink argumen root — aman
                    val proc = ProcessBuilder("su", "-c", "rm -rf $escaped").start()
                    val finished = proc.waitFor(180, TimeUnit.SECONDS)
                    if (!finished || proc.exitValue() != 0) return false
                    // verifikasi node benar-benar hilang
                    val check = ProcessBuilder("su", "-c", "[ ! -e $escaped ]").start()
                    check.waitFor(15, TimeUnit.SECONDS) && check.exitValue() == 0
                } catch (e: Exception) {
                    false
                }
            }
            ScanMode.SHIZUKU -> {
                try {
                    val out = ShizukuRunner.runCommandText(
                        "rm -rf $escaped; rc=\$?; if [ ! -e $escaped ]; then echo GONE; else echo FAIL_\$rc; fi"
                    ) ?: return false
                    out.contains("GONE")
                } catch (e: Exception) {
                    false
                }
            }
            ScanMode.NORMAL -> {
                try {
                    val isLink = try {
                        java.nio.file.Files.isSymbolicLink(File(path).toPath())
                    } catch (e: Exception) {
                        false
                    }
                    val ok = if (isLink) File(path).delete() else File(path).deleteRecursively()
                    ok && !File(path).exists()
                } catch (e: Exception) {
                    false
                }
            }
        }
    }

    private fun onDeleteSuccess(node: Node) {
        val sr = scanRoot ?: return
        if (!TreeOps.detachFromParent(node)) return

        // Refresh grafik, daftar, dan statistik dari pohon yang sudah di-update
        val cur = currentNode ?: sr
        val (dirs, files) = TreeOps.recomputeTotals(sr)
        currentInfo.text = "${SizeUtil.shortPath(cur.displayName ?: cur.path)}  ·  ${SizeUtil.format(cur.size)}"
        adapter.setData(cur.children, cur.size)
        sunburst.centerNode = cur
        statsBar.text = "${SizeUtil.formatCount(dirs)} folder · " +
            "${SizeUtil.formatCount(files)} file · " +
            "${SizeUtil.format(sr.size)} · dihapus"
        updateStorageInfo(scanPathUsed)
        Toast.makeText(this, "Terhapus: ${node.name} (${SizeUtil.format(node.size)})", Toast.LENGTH_LONG).show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this, R.style.Filelight_Dialog)
            .setIcon(R.drawable.ic_launcher)
            .setTitle("DiskLight untuk Android")
            .setMessage(
                "Aplikasi visualisasi pemakaian disk Android, ditulis ulang dari nol dalam Kotlin.\n\n" +
                    "• Grafik sunburst interaktif gaya Filelight\n" +
                    "• Mode Root penuh: memindai /data, /system, dll lewat su\n" +
                    "• Mode Shizuku (ADB): perintah shell sebagai uid 2000 (shell)\n" +
                    "• Mode Normal untuk /sdcard\n" +
                    "• Daftar file/folder terbesar dengan persentase\n" +
                    "• Ketuk segmen grafik atau item untuk menelusuri\n" +
                    "• Tekan-tahan item (daftar atau grafik) untuk menghapus\n\n" +
                    "Terinspirasi oleh KDE Filelight; aplikasi ini ditulis ulang " +
                    "dari nol tanpa memakai kode GPL apa pun, berlisensi MIT. " +
                    "Merupakan proyek independen dan tidak berafiliasi dengan KDE e.V.\n\n" +
                    "Versi 1.1.0"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onDestroy() {
        try { ShizukuRunner.unbind() } catch (_: Exception) {}
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun showStorageAccessHintIfNeeded(onDone: () -> Unit) {
        if (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) {
            onDone()
            return
        }
        AlertDialog.Builder(this, R.style.Filelight_Dialog)
            .setTitle("Akses semua file?")
            .setMessage(
                "Untuk memindai penyimpanan internal tanpa root, DiskLight memerlukan izin " +
                    "\"Akses ke semua file\". Anda bisa mengaktifkannya di Pengaturan.\n\n" +
                    "(Dengan root aktif, izin ini tidak diperlukan.)"
            )
            .setPositiveButton("Buka Pengaturan") { _, _ ->
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (e2: Exception) {
                        Toast.makeText(this, "Buka Pengaturan → Aplikasi → DiskLight → Akses semua file", Toast.LENGTH_LONG).show()
                    }
                }
                onDone()
            }
            .setNegativeButton("Nanti") { _, _ -> onDone() }
            .show()
    }
}
