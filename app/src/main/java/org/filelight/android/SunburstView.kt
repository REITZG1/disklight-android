package org.filelight.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.HapticFeedbackConstants
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Grafik sunburst (cincin konsentris) khas KDE Filelight.
 *
 * - Pusat lingkaran = folder yang sedang dilihat.
 * - Cincin-cincin keluar = sub-folder/file, lebar sudut proporsional
 *   terhadap ukurannya.
 * - Ketuk segmen folder untuk masuk; ketuk pusat untuk naik satu level.
 * - Ketuk segmen file untuk memilihnya.
 */
class SunburstView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Segment(
        val node: Node,
        val ring: Int,
        val start: Float,   // derajat 0..360, 0 = atas, searah jarum jam
        val sweep: Float,   // derajat
        val color: Int
    )

    var centerNode: Node? = null
        set(value) {
            field = value
            selectedNode = null
            rebuild()
        }

    var onNavigateUp: (() -> Unit)? = null
    var onNavigateInto: ((Node) -> Unit)? = null
    var onSelect: ((Node) -> Unit)? = null

    /** Dipanggil saat segmen ditekan-tahan (tap & hold) — untuk aksi seperti hapus. */
    var onLongSelect: ((Node) -> Unit)? = null

    private val segments = ArrayList<Segment>()
    private var selectedNode: Node? = null

    private val density = resources.displayMetrics.density
    private val pad = 10f * density

    private var ringCount = 0
    private var ringThickness = 0f
    private var centerRadius = 0f
    private var outerRadius = 0f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokeGapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#232629")
        strokeWidth = 2f
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#eff0f1")
        strokeWidth = 3f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 11f * density
        isFakeBoldText = true
        setShadowLayer(3f, 0f, 0f, Color.parseColor("#88000000"))
    }
    private val centerTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#eff0f1")
        textAlign = Paint.Align.CENTER
        textSize = 15f * density
        isFakeBoldText = true
    }
    private val centerSizePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3daee9")
        textAlign = Paint.Align.CENTER
        textSize = 13f * density
        isFakeBoldText = true
    }
    private val centerSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#a1a9b1")
        textAlign = Paint.Align.CENTER
        textSize = 10f * density
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1b1e20")
    }

    companion object {
        /** Hue dasar bergaya KDE Breeze — dibedakan per anak tingkat pertama. */
        private val PALETTE = intArrayOf(
            0xFF3DAEE9.toInt(), // breeze blue
            0xFF1CDC9A.toInt(), // hijau
            0xFFFDBC4B.toInt(), // kuning
            0xFFDA4453.toInt(), // merah
            0xFF9B59B6.toInt(), // ungu
            0xFF1ABC9C.toInt(), // teal
            0xFFE67E22.toInt(), // oranye
            0xFF2ECC71.toInt(), // hijau muda
            0xFF3498DB.toInt(), // biru
            0xFFE84393.toInt(), // pink
            0xFFF39C12.toInt(), // oranye tua
            0xFF00CEC9.toInt()  // turquoise
        )

        private const val MAX_RINGS = 6
        private const val MIN_LABEL_SWEEP_DEG = 32f
        private val HSV = FloatArray(3)
    }

    /* ─────────────────────────── rekonstruksi ─────────────────────────── */

    private fun rebuild() {
        segments.clear()
        val cn = centerNode
        if (cn == null || cn.size <= 0L || width == 0 || height == 0) {
            invalidate()
            return
        }
        computeRadii()
        addLevel(cn.children, 0, 0f, 360f, null)
        invalidate()
    }

    private fun computeRadii() {
        outerRadius = min(width, height) / 2f - pad
        centerRadius = outerRadius * 0.28f
        val available = outerRadius - centerRadius
        val minRing = 17f * density
        ringCount = (available / minRing).toInt().coerceIn(1, MAX_RINGS)
        ringThickness = available / ringCount
    }

    private fun addLevel(
        children: List<Node>,
        ring: Int,
        start: Float,
        sweep: Float,
        parentColor: Int?
    ) {
        if (ring >= ringCount || children.isEmpty() || sweep <= 0.4f) return
        val total = children.sumOf { it.size }
        if (total <= 0L) return

        var acc = start
        children.forEachIndexed { index, child ->
            val fraction = child.size.toFloat() / total.toFloat()
            val childSweep = sweep * fraction
            val color = colorFor(child, index, ring, parentColor)
            segments.add(Segment(child, ring, acc, childSweep, color))
            if (child.isDirectory && child.children.isNotEmpty()) {
                addLevel(child.children, ring + 1, acc, childSweep, color)
            }
            acc += childSweep
        }
    }

    private fun colorFor(node: Node, index: Int, ring: Int, parentColor: Int?): Int {
        val seed = (node.name.hashCode().toLong() and 0xFFFFFFFFL)
        if (parentColor != null) {
            // Anak dari parent yang sama: geser hue secara deterministik.
            Color.colorToHSV(parentColor, HSV)
            HSV[0] = (HSV[0] + 14f + (seed % 40L).toFloat()) % 360f
            HSV[1] = (HSV[1] * 0.96f).coerceIn(0.25f, 0.95f)
            HSV[2] = (HSV[2] * (0.82f + 0.10f * ring.toFloat())).coerceIn(0.30f, 0.98f)
        } else {
            val base = PALETTE[index % PALETTE.size]
            Color.colorToHSV(base, HSV)
            HSV[0] = (HSV[0] + ((seed % 24L).toFloat() - 12f)).let { ((it % 360f) + 360f) % 360f }
        }
        if (!node.isDirectory) {
            // File: lebih pudar/redup agar folder menonjol.
            HSV[1] *= 0.45f
            HSV[2] *= if (node.synthetic) 0.55f else 0.78f
        }
        return Color.HSVToColor(HSV)
    }

    /* ────────────────────────────── render ────────────────────────────── */

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val cx = width / 2f
        val cy = height / 2f
        if (centerNode == null) {
            // Placeholder kosong
            bgPaint.color = Color.parseColor("#202327")
            canvas.drawCircle(cx, cy, min(width, height) / 2f - pad, bgPaint)
            fillPaint.style = Paint.Style.STROKE
            fillPaint.color = Color.parseColor("#3daee9")
            fillPaint.strokeWidth = 2f * density
            canvas.drawCircle(cx, cy, min(width, height) / 2f - pad - 1f * density, fillPaint)
            fillPaint.style = Paint.Style.FILL
            fillPaint.strokeWidth = 0f
            drawCenterText(canvas, cx, cy, "DiskLight", "tekan PINDAI", null)
            return
        }
        if (centerNode!!.size <= 0L && segments.isEmpty()) {
            bgPaint.color = Color.parseColor("#1b1e20")
            canvas.drawCircle(cx, cy, min(width, height) / 2f - pad, bgPaint)
            drawCenterText(canvas, cx, cy, "Kosong", "tidak ada data", null)
            return
        }

        computeRadii()
        val R = outerRadius

        // Latar disk
        bgPaint.color = Color.parseColor("#1b1e20")
        canvas.drawCircle(cx, cy, R, bgPaint)

        val rect = RectF()
        for (s in segments) {
            val rOuter = centerRadius + (s.ring + 1) * ringThickness
            val inset = 1.2f
            rect.set(cx - rOuter + inset, cy - rOuter + inset, cx + rOuter - inset, cy + rOuter - inset)

            fillPaint.color = s.color
            canvas.drawArc(rect, s.start - 90f, s.sweep, true, fillPaint)
            // Garis celah (gap) antar segmen
            rect.set(cx - rOuter, cy - rOuter, cx + rOuter, cy + rOuter)
            canvas.drawArc(rect, s.start - 90f, s.sweep, true, strokeGapPaint)
        }

        // Label pada segmen yang cukup besar
        if (ringThickness >= 14f) {
            for (s in segments) {
                if (s.sweep < MIN_LABEL_SWEEP_DEG) continue
                val mid = Math.toRadians((s.start + s.sweep / 2f - 90f).toDouble())
                val rMid = centerRadius + (s.ring + 0.5f) * ringThickness
                val x = cx + (cos(mid) * rMid).toFloat()
                val y = cy + (sin(mid) * rMid).toFloat()
                val maxWidth = (s.sweep / 360f * (2.0 * Math.PI * rMid).toFloat()) * 0.82f
                labelPaint.textSize = min(11f * density, ringThickness * 0.44f)
                val text = ellipsize(s.node.name, maxWidth, labelPaint)
                if (text.isNotEmpty()) {
                    canvas.drawText(text, x, y + labelPaint.textSize / 3f, labelPaint)
                }
            }
        }

        // Highlight segmen terpilih
        val sel = selectedNode
        if (sel != null) {
            for (s in segments) {
                if (s.node === sel) {
                    val rOuter = centerRadius + (s.ring + 1) * ringThickness - 1.2f
                    rect.set(cx - rOuter, cy - rOuter, cx + rOuter, cy + rOuter)
                    canvas.drawArc(rect, s.start - 90f, s.sweep, true, selectionPaint)
                }
            }
        }

        // Pusat lingkaran
        val cn = centerNode!!
        canvas.drawCircle(cx, cy, centerRadius - 2f, fillCenter())
        // Cincin aksen bila bisa naik
        if (cn.parent != null) {
            fillPaint.color = Color.parseColor("#3daee9")
            fillPaint.style = Paint.Style.STROKE
            fillPaint.strokeWidth = 2f * density
            canvas.drawCircle(cx, cy, centerRadius - 3f, fillPaint)
            fillPaint.style = Paint.Style.FILL
        }

        drawCenterText(
            canvas,
            cx, cy,
            ellipsize(cn.displayName ?: cn.name, centerRadius * 1.7f, centerTitlePaint),
            SizeUtil.format(cn.size),
            if (cn.parent != null) "ketuk utk naik" else null
        )
    }

    private fun fillCenter(): Paint {
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = Color.parseColor("#2a2e32")
        fillPaint.strokeWidth = 0f
        return fillPaint
    }

    private fun drawCenterText(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        title: String,
        size: String?,
        hint: String?
    ) {
        val ty = cy - if (size != null) 4f * density else 0f
        canvas.drawText(title, cx, ty, centerTitlePaint)
        if (size != null) {
            canvas.drawText(size, cx, cy + 12f * density, centerSizePaint)
        }
        if (hint != null) {
            canvas.drawText(hint, cx, cy + 27f * density, centerSubPaint)
        }
    }

    private fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
        if (paint.measureText(text) <= maxWidth) return text
        var s = text
        while (s.isNotEmpty() && paint.measureText(s + "…") > maxWidth) {
            s = s.dropLast(1)
        }
        return if (s.isEmpty()) "" else s + "…"
    }

    /* ───────────────────────────── interaksi ──────────────────────────── */

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (centerNode == null) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = event.eventTime
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (distToDown(event) > 24f) downTime = 0L
                return true
            }
            MotionEvent.ACTION_UP -> {
                val moved = distToDown(event) > 24f
                val heldMs = if (downTime == 0L) 0L else event.eventTime - downTime
                val longPress = !moved && heldMs > 480L
                val hit = hitTestNode(event.x, event.y)
                if (longPress) {
                    if (hit != null) {
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        onLongSelect?.invoke(hit)
                    }
                    return true
                }
                handleTap(event.x, event.y, hit)
                return true
            }
        }
        return true
    }

    private fun distToDown(event: MotionEvent): Float {
        val dx = event.x - downX
        val dy = event.y - downY
        return sqrt(dx * dx + dy * dy)
    }

    private fun hitTestNode(x: Float, y: Float): Node? {
        val cx = width / 2f
        val cy = height / 2f
        val dx = x - cx
        val dy = y - cy
        val dist = sqrt(dx * dx + dy * dy)
        if (dist <= centerRadius) return null // pusat = naik, bukan untuk aksi konteks

        rebuildHitRadii()
        val ring = floor(((dist - centerRadius) / ringThickness).toDouble()).toInt()
        if (ring < 0 || ring >= ringCount) return null

        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
        if (angle < 0f) angle += 360f

        for (s in segments) {
            if (s.ring != ring) continue
            val end = s.start + s.sweep
            if (angle >= s.start && angle < end) return s.node
        }
        return null
    }

    private fun handleTap(x: Float, y: Float, hit: Node?) {
        val cx = width / 2f
        val cy = height / 2f
        val dx = x - cx
        val dy = y - cy
        val dist = sqrt(dx * dx + dy * dy)

        if (dist <= centerRadius) {
            selectedNode = null
            onNavigateUp?.invoke()
            invalidate()
            return
        }

        val node = hit ?: return
        if (node.isDirectory && node.children.isNotEmpty()) {
            selectedNode = null
            onNavigateInto?.invoke(node)
        } else {
            selectedNode = if (selectedNode === node) null else node
            selectedNode?.let { onSelect?.invoke(it) }
            invalidate()
        }
    }

    private fun rebuildHitRadii() {
        if (outerRadius == 0f || width > 0) computeRadii()
    }

    fun clearSelection() {
        selectedNode = null
        invalidate()
    }
}
