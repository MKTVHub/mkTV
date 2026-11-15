package com.mktvhub.mktv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View

/**
 * On-screen pointer driven by the D-pad. Movement runs on a Choreographer loop while
 * a direction is held and (unless turned off) accelerates the longer it is held. When the pointer is pushed
 * against an edge, [onEdgeScroll] is called so the page can scroll.
 */
class CursorView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var x0 = -1f; private set
    var y0 = -1f; private set

    private var placed = false

    /** Jump mode: draw a focus ring around [ring] instead of the arrow. */
    var jumpMode = false
        set(v) { field = v; invalidate() }
    private var ring: android.graphics.RectF? = null
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF818CF8.toInt() }
    private val ringGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x55818CF8 }

    fun showRing(r: android.graphics.RectF?) {
        ring = r
        if (r != null) { alpha = 1f; animate().cancel() }
        invalidate()
    }

    fun ringCenter(): android.graphics.PointF? = ring?.let { android.graphics.PointF(it.centerX(), it.centerY()) }

    var onEdgeScroll: ((dx: Int, dy: Int) -> Unit)? = null
    /** Called every frame the pointer moves. */
    var onMove: ((x: Float, y: Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    /** Settings → Navigation; set by the activity (not read from Prefs every frame). See [PointerMotion]. */
    var speedLevel = PointerMotion.NORMAL
    var accelerate = true

    private var left = false; private var right = false; private var up = false; private var down = false
    private var heldSince = 0L
    private var lastFrameNs = 0L
    private var lastScrollMs = 0L
    private var running = false
    private var lastActivity = System.currentTimeMillis()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.5f * density }
    private val arrow = Path()

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return
        if (!placed) { x0 = w / 2f; y0 = h / 2f; placed = true }
        x0 = x0.coerceIn(0f, w - 1f); y0 = y0.coerceIn(0f, h - 1f)
    }

    fun press(dir: Int) {
        if (!left && !right && !up && !down) heldSince = System.currentTimeMillis()
        when (dir) { L -> left = true; R -> right = true; U -> up = true; D -> down = true }
        wake()
        if (!running) { running = true; lastFrameNs = 0L; Choreographer.getInstance().postFrameCallback(frame) }
    }

    fun release(dir: Int) {
        when (dir) { L -> left = false; R -> right = false; U -> up = false; D -> down = false }
    }

    fun releaseAll() { left = false; right = false; up = false; down = false }

    /** Phone touchpad: move by (dx, dy) screen px. Pushing past an edge scrolls, like a held D-pad. */
    fun moveBy(dx: Float, dy: Float) {
        if (width == 0 || height == 0) return
        val nx = (x0 + dx).coerceIn(0f, width - 1f)
        val ny = (y0 + dy).coerceIn(0f, height - 1f)
        val edgeX = if (dx != 0f && nx == x0) (if (dx > 0) 1 else -1) else 0
        val edgeY = if (dy != 0f && ny == y0) (if (dy > 0) 1 else -1) else 0
        if (edgeX != 0 || edgeY != 0) onEdgeScroll?.invoke(edgeX, edgeY)
        x0 = nx; y0 = ny
        wake()
        onMove?.invoke(x0, y0)
        invalidate()
    }

    /** Makes the pointer visible again after it auto-hid. */
    fun wake() {
        lastActivity = System.currentTimeMillis()
        if (alpha < 1f) { animate().cancel(); alpha = 1f }
        removeCallbacks(hide)
        postDelayed(hide, HIDE_AFTER_MS)
    }

    private val hide = Runnable {
        if (!jumpMode && System.currentTimeMillis() - lastActivity >= HIDE_AFTER_MS) animate().alpha(0f).setDuration(400).start()
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            val moving = left || right || up || down
            if (!moving) { running = false; return }
            val dt = if (lastFrameNs == 0L) 1f / 60 else ((now - lastFrameNs) / 1e9f).coerceAtMost(0.05f)
            lastFrameNs = now
            val held = (System.currentTimeMillis() - heldSince).coerceAtLeast(0)
            val speed = PointerMotion.speed(held, speedLevel, accelerate) * density
            val dx = (if (right) 1 else 0) - (if (left) 1 else 0)
            val dy = (if (down) 1 else 0) - (if (up) 1 else 0)
            val nx = (x0 + dx * speed * dt).coerceIn(0f, width - 1f)
            val ny = (y0 + dy * speed * dt).coerceIn(0f, height - 1f)

            // Pushing against an edge scrolls the page instead.
            val edgeX = if (dx != 0 && nx == x0) dx else 0
            val edgeY = if (dy != 0 && ny == y0) dy else 0
            val ms = System.currentTimeMillis()
            if ((edgeX != 0 || edgeY != 0) && ms - lastScrollMs > 40) {
                lastScrollMs = ms
                onEdgeScroll?.invoke(edgeX, edgeY)
            }
            x0 = nx; y0 = ny
            onMove?.invoke(x0, y0)
            lastActivity = ms
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (jumpMode) {
            val r = ring ?: return
            val pad = 4 * density
            // Keep the whole ring (and its glow) on screen, e.g. for bars docked to the screen edge.
            val inset = 7 * density
            val rr = android.graphics.RectF(
                (r.left - pad).coerceAtLeast(inset), (r.top - pad).coerceAtLeast(inset),
                (r.right + pad).coerceAtMost(width - inset), (r.bottom + pad).coerceAtMost(height - inset),
            )
            ringGlow.strokeWidth = 10 * density
            canvas.drawRoundRect(rr, 10 * density, 10 * density, ringGlow)
            ringPaint.strokeWidth = 3.5f * density
            canvas.drawRoundRect(rr, 10 * density, 10 * density, ringPaint)
            return
        }
        if (x0 < 0) return
        val s = density
        arrow.reset()
        arrow.moveTo(x0, y0)
        arrow.lineTo(x0, y0 + 22 * s)
        arrow.lineTo(x0 + 6 * s, y0 + 17 * s)
        arrow.lineTo(x0 + 10 * s, y0 + 26 * s)
        arrow.lineTo(x0 + 14 * s, y0 + 24 * s)
        arrow.lineTo(x0 + 10 * s, y0 + 15.5f * s)
        arrow.lineTo(x0 + 17 * s, y0 + 15.5f * s)
        arrow.close()
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, stroke)
    }

    companion object {
        const val L = 0; const val R = 1; const val U = 2; const val D = 3
        private const val HIDE_AFTER_MS = 6_000L
    }
}
