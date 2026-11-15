package com.mktvhub.mktv

import android.app.Activity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View

/**
 * A phone key into whichever mkTV screen is in front (the browser, or the native player over it).
 * Keys dispatched straight into an activity skip the step of Android's input pipeline that moves focus
 * between views on arrow keys (only scrolling containers handled ▲▼ by themselves, so ◀▶ between Home's
 * buttons did nothing). Do that focus move here when nothing else consumed the arrow, as a hardware D-pad would.
 */
fun Activity.dispatchRemoteKey(e: KeyEvent): Boolean {
    e.source = InputDevice.SOURCE_DPAD
    if (dispatchKeyEvent(e) || e.action != KeyEvent.ACTION_DOWN) return true
    val dir = when (e.keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP; KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT; KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
        else -> return false
    }
    return currentFocus?.focusSearch(dir)?.requestFocus(dir) == true
}
