package com.mktvhub.mktv

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * The native player's progress bar: track, buffered part, played part in the accent gradient, and while
 * ◀/▶ is held a preview of where the seek will land (time bubble above it, tick at the real position).
 * Drawn by hand so it looks the same on every TV; keys are handled by [PlayerActivity].
 */
class PlayerBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var duration = 0L
    var position = 0L
    var buffered = 0L
    /** Seek target while a seek key is held, or -1. */
    var preview = -1L

    private val dp = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
    private val buffer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x80FFFFFF.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9111317.toInt() }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 15 * dp; isFakeBoldText = true; textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    init {
        isFocusable = true
        // Reaching the bar with ▲ shouldn't draw the system's focus highlight over it; the bar grows instead.
        if (android.os.Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
    }

    fun update(duration: Long, position: Long, buffered: Long, preview: Long) {
        if (duration == this.duration && position == this.position && buffered == this.buffered && preview == this.preview) return
        this.duration = duration; this.position = position; this.buffered = buffered; this.preview = preview
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fill.shader = LinearGradient(pad(), 0f, w - pad(), 0f, 0xFF7C3AED.toInt(), 0xFFDB2777.toInt(), Shader.TileMode.CLAMP)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    private fun pad() = 10 * dp

    override fun onDraw(canvas: Canvas) {
        val left = pad(); val right = width - pad(); val w = right - left
        val thick = (if (isFocused) 8 else 5) * dp
        val cy = height - 12 * dp
        val r = thick / 2
        fun x(ms: Long) = if (duration > 0) left + w * (ms.coerceIn(0, duration).toFloat() / duration) else left

        rect.set(left, cy - r, right, cy + r); canvas.drawRoundRect(rect, r, r, track)
        if (duration <= 0) return
        rect.set(left, cy - r, maxOf(x(buffered), x(position)), cy + r); canvas.drawRoundRect(rect, r, r, buffer)
        val head = x(if (preview >= 0) preview else position)
        rect.set(left, cy - r, maxOf(head, left + thick), cy + r); canvas.drawRoundRect(rect, r, r, fill)

        if (preview >= 0) {
            // Where playback really is, so a long hold shows how far the jump goes.
            val px = x(position)
            rect.set(px - 1.5f * dp, cy - r - 3 * dp, px + 1.5f * dp, cy + r + 3 * dp); canvas.drawRoundRect(rect, dp, dp, white)
            canvas.drawCircle(head, cy, 10 * dp, white)
            val text = "${PlayerUi.clock(preview)}   ${PlayerUi.delta(preview - position)}"
            val bw = label.measureText(text) + 28 * dp; val bh = 30 * dp
            val bx = (head - bw / 2).coerceIn(0f, width - bw)
            rect.set(bx, cy - 22 * dp - bh, bx + bw, cy - 22 * dp); canvas.drawRoundRect(rect, bh / 2, bh / 2, bubble)
            canvas.drawText(text, bx + bw / 2, rect.centerY() - (label.descent() + label.ascent()) / 2, label)
        } else if (isFocused) {
            canvas.drawCircle(head, cy, 9 * dp, white)
        }
    }
}
