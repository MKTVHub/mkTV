package com.mktvhub.mktv

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Parental controls number pad: a full-screen overlay with a 3×4 grid of big keys (1–9, ✕, 0, ⌫) and four dots.
 * There is no OK key: the 4th digit submits. The remote's number keys and the phone remote's typing work too.
 * Wrong tries and the wait after five of them are kept in [Prefs.pinLockout]; the rules are in [Parental].
 */
class PinPad(private val activity: Activity, private val root: FrameLayout, private val prefs: Prefs, private val host: Host) {

    interface Host {
        /** The pad is on screen: give it the remote, starting on [focus]. */
        fun shown(focus: View)
        /** The pad closed: focus goes back to [previous] (what had it before), or wherever makes sense. */
        fun hidden(previous: View?)
    }

    private enum class Step { VERIFY, NEW, CONFIRM }

    private var overlay: FrameLayout? = null
    private lateinit var titleView: TextView
    private lateinit var messageView: TextView
    private lateinit var dotsRow: LinearLayout
    private lateinit var grid: LinearLayout
    /** Row by row: 1 2 3 / 4 5 6 / 7 8 9 / ✕ 0 ⌫. */
    private val keys = ArrayList<View>()

    private var step = Step.VERIFY
    private var entered = ""
    private var firstPin = ""
    private var verifyTitle = ""
    /** Change PIN: the current PIN, then the new one twice, in one go. */
    private var thenCreate = false
    private var error: String? = null
    /** The 4th dot shows for a moment before the PIN is checked; input waits meanwhile. */
    private var submitting = false
    private var onVerified: (() -> Unit)? = null
    private var onCreated: ((String) -> Unit)? = null
    private var onCancel: (() -> Unit)? = null
    private var previous: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { paintMessage(); if (locked()) handler.postDelayed(this, 1000) }
    }

    val isOpen get() = overlay?.visibility == View.VISIBLE

    /** Asks for the current PIN; [onOk] runs after the pad has closed. */
    fun verify(title: String, onCancel: () -> Unit = {}, onOk: () -> Unit) {
        open(Step.VERIFY, title, thenCreate = false, onCancel = onCancel, onVerified = onOk, onCreated = null)
    }

    /** A new PIN, entered twice; [onCreated] gets its salted hash ([Parental.record]). */
    fun create(onCancel: () -> Unit = {}, onCreated: (String) -> Unit) {
        open(Step.NEW, "", thenCreate = false, onCancel = onCancel, onVerified = null, onCreated = onCreated)
    }

    /** The current PIN, then a new one twice. */
    fun change(onCancel: () -> Unit = {}, onCreated: (String) -> Unit) {
        open(Step.VERIFY, "Enter the current PIN", thenCreate = true, onCancel = onCancel, onVerified = null, onCreated = onCreated)
    }

    /** Back, the phone's Home key, or mkTV going to the background. */
    fun cancel() {
        if (!isOpen) return
        val c = onCancel
        close()
        c?.invoke()
    }

    /**
     * Digits and Delete from the remote. Menu and Search are swallowed so nothing opens over the pad.
     * Arrows and OK are left to normal focus navigation between the keys.
     */
    fun handleKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        val digit = when (code) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
            else -> -1
        }
        val del = code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_FORWARD_DEL
        val blocked = code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_BOOKMARK || code == KeyEvent.KEYCODE_SEARCH
        if (digit < 0 && !del && !blocked) return false
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            if (digit >= 0) digit(digit) else if (del) backspace()
        }
        return true
    }

    /** Phone remote typing: digits go in, anything else is ignored. */
    fun typed(text: String, deletes: Int, clear: Boolean) {
        if (clear) { entered = ""; paintDots() }
        repeat(deletes) { backspace() }
        text.filter { it in '0'..'9' }.forEach { digit(it - '0') }
    }

    // ---- State ----

    private fun open(s: Step, title: String, thenCreate: Boolean, onCancel: () -> Unit, onVerified: (() -> Unit)?, onCreated: ((String) -> Unit)?) {
        if (isOpen) cancel() // a second request replaces the first, which counts as cancelled
        val view = overlay ?: build()
        step = s; verifyTitle = title; this.thenCreate = thenCreate
        this.onCancel = onCancel; this.onVerified = onVerified; this.onCreated = onCreated
        entered = ""; firstPin = ""; error = null; submitting = false
        previous = activity.currentFocus
        paint()
        view.visibility = View.VISIBLE
        handler.removeCallbacks(tick)
        if (locked()) handler.post(tick)
        host.shown(keys[0])
    }

    private fun close() {
        val view = overlay ?: return
        handler.removeCallbacksAndMessages(null)
        view.visibility = View.GONE
        onCancel = null; onVerified = null; onCreated = null
        entered = ""; firstPin = ""
        val prev = previous
        previous = null
        host.hidden(prev)
    }

    private fun locked() = step == Step.VERIFY && Parental.lockedFor(prefs.pinLockout, System.currentTimeMillis()) > 0

    private fun digit(d: Int) {
        if (!isOpen || submitting) return
        if (locked()) { shake(); return }
        if (entered.length >= Parental.PIN_LENGTH) return
        entered += d
        error = null
        paintDots(); paintMessage()
        if (entered.length == Parental.PIN_LENGTH) {
            submitting = true
            handler.postDelayed({ submitting = false; submit(entered) }, 160)
        }
    }

    private fun backspace() {
        if (!isOpen || submitting || entered.isEmpty()) return
        entered = entered.dropLast(1)
        paintDots()
    }

    private fun submit(pin: String) {
        entered = ""
        when (step) {
            Step.VERIFY -> {
                if (Parental.verify(pin, prefs.pinRecord)) {
                    prefs.pinLockout = Parental.Lockout()
                    if (thenCreate) { step = Step.NEW; paint(); return }
                    val ok = onVerified
                    close(); ok?.invoke()
                    return
                }
                val now = System.currentTimeMillis()
                val s = Parental.afterWrong(prefs.pinLockout, now)
                prefs.pinLockout = s
                val left = Parental.triesLeft(s)
                error = if (Parental.lockedFor(s, now) > 0) null else "Wrong PIN · $left ${if (left == 1) "try" else "tries"} left"
                shake()
                if (locked()) { handler.removeCallbacks(tick); handler.post(tick) }
            }
            Step.NEW -> { firstPin = pin; step = Step.CONFIRM }
            Step.CONFIRM -> {
                if (pin == firstPin) {
                    val done = onCreated
                    val rec = Parental.record(pin)
                    close(); done?.invoke(rec)
                    return
                }
                firstPin = ""; step = Step.NEW
                error = "Those didn't match. Choose the new PIN again."
                shake()
            }
        }
        paint()
    }

    // ---- Drawing ----

    private fun paint() {
        titleView.text = when (step) {
            Step.VERIFY -> verifyTitle
            Step.NEW -> "Choose a 4-digit PIN"
            Step.CONFIRM -> "Enter the new PIN again"
        }
        paintDots(); paintMessage()
    }

    private fun paintDots() {
        for (i in 0 until dotsRow.childCount) {
            dotsRow.getChildAt(i).background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                if (i < entered.length) setColor(0xFF818CF8.toInt()) else setStroke(dp(2), 0xFF6B7280.toInt())
            }
        }
    }

    private fun paintMessage() {
        val wait = Parental.lockedFor(prefs.pinLockout, System.currentTimeMillis())
        val (text, color) = when {
            step == Step.VERIFY && wait > 0 -> "Too many tries. Try again in ${(wait + 999) / 1000} s" to 0xFFFBBF24.toInt()
            error != null -> error!! to 0xFFFCA5A5.toInt()
            step == Step.NEW -> "Digits on the remote or the keys below · Back to cancel" to 0xFF9CA3AF.toInt()
            step == Step.CONFIRM -> "The same 4 digits once more" to 0xFF9CA3AF.toInt()
            else -> "Digits on the remote or the keys below · Back to cancel" to 0xFF9CA3AF.toInt()
        }
        messageView.text = text
        messageView.setTextColor(color)
        grid.alpha = if (step == Step.VERIFY && wait > 0) 0.45f else 1f
    }

    private fun shake() {
        android.animation.ObjectAnimator.ofFloat(dotsRow, "translationX", 0f, 26f, -26f, 18f, -18f, 8f, -8f, 0f).setDuration(420).start()
        paintDots()
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    private fun build(): FrameLayout {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundResource(R.drawable.panel_bg)
            setPadding(dp(36), dp(24), dp(36), dp(24))
            isClickable = true
        }
        card.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_lock)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFF818CF8.toInt())
        }, LinearLayout.LayoutParams(dp(30), dp(30)))
        titleView = TextView(activity).apply {
            setTextColor(android.graphics.Color.WHITE); textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        card.addView(titleView)
        messageView = TextView(activity).apply { textSize = 14f; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) }
        card.addView(messageView, LinearLayout.LayoutParams(dp(330), ViewGroup.LayoutParams.WRAP_CONTENT))
        dotsRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        repeat(Parental.PIN_LENGTH) { i ->
            dotsRow.addView(View(activity), LinearLayout.LayoutParams(dp(18), dp(18)).apply { if (i > 0) marginStart = dp(18) })
        }
        card.addView(dotsRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })

        grid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val labels = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "cancel", "0", "del")
        labels.chunked(3).forEach { row ->
            val line = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEachIndexed { c, label ->
                val key: View = when (label) {
                    "cancel" -> iconKey(R.drawable.ic_close, "Cancel") { cancel() }
                    "del" -> iconKey(R.drawable.ic_backspace, "Delete") { backspace() }
                    else -> TextView(activity).apply {
                        text = label
                        gravity = Gravity.CENTER
                        setTextColor(android.graphics.Color.WHITE); textSize = 26f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setOnClickListener { digit(label.toInt()) }
                    }
                }
                key.apply {
                    id = View.generateViewId()
                    setBackgroundResource(R.drawable.toolbar_button)
                    isFocusable = true; isClickable = true
                }
                keys += key
                line.addView(key, LinearLayout.LayoutParams(dp(96), dp(58)).apply { if (c > 0) marginStart = dp(10) })
            }
            grid.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }
        card.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        // ▲▼◀▶ stay on the grid: edge keys point at themselves.
        keys.forEachIndexed { i, k ->
            val r = i / 3; val c = i % 3
            k.nextFocusUpId = keys[if (r > 0) i - 3 else i].id
            k.nextFocusDownId = keys[if (r < 3) i + 3 else i].id
            k.nextFocusLeftId = keys[if (c > 0) i - 1 else i].id
            k.nextFocusRightId = keys[if (c < 2) i + 1 else i].id
        }

        val view = FrameLayout(activity).apply {
            setBackgroundColor(0xD9000000.toInt())
            isClickable = true // taps around the card don't reach the page
            elevation = dp(40).toFloat() // above the panels, which have elevation of their own
            visibility = View.GONE
            addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = view
        return view
    }

    private fun iconKey(icon: Int, label: String, onClick: () -> Unit) = ImageView(activity).apply {
        setImageResource(icon)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = label
        setOnClickListener { onClick() }
    }
}
