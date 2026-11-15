package com.mktvhub.mktv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerMotionTest {
    private val eps = 0.01f

    @Test fun normalRampMatchesTheOriginalCurve() {
        assertEquals(260f, PointerMotion.speed(0, PointerMotion.NORMAL, true), eps)
        assertEquals(780f, PointerMotion.speed(450, PointerMotion.NORMAL, true), eps)
        assertEquals(1300f, PointerMotion.speed(900, PointerMotion.NORMAL, true), eps)
    }

    @Test fun rampStopsAtFullSpeed() {
        assertEquals(1300f, PointerMotion.speed(60_000, PointerMotion.NORMAL, true), eps)
    }

    @Test fun negativeHoldCountsAsJustPressed() {
        assertEquals(260f, PointerMotion.speed(-50, PointerMotion.NORMAL, true), eps)
    }

    @Test fun accelerationOffIsSteady() {
        val a = PointerMotion.speed(0, PointerMotion.NORMAL, false)
        assertEquals(PointerMotion.STEADY_DP_S, a, eps)
        assertEquals(a, PointerMotion.speed(5_000, PointerMotion.NORMAL, false), eps)
    }

    @Test fun levelsScaleSpeed() {
        val slow = PointerMotion.speed(300, PointerMotion.SLOW, true)
        val normal = PointerMotion.speed(300, PointerMotion.NORMAL, true)
        val fast = PointerMotion.speed(300, PointerMotion.FAST, true)
        assertTrue(slow < normal && normal < fast)
        assertEquals(normal * 0.6f, slow, eps)
        assertEquals(normal * 1.6f, fast, eps)
    }

    @Test fun outOfRangeLevelsClamp() {
        assertEquals(PointerMotion.SLOW, PointerMotion.level(-3))
        assertEquals(PointerMotion.FAST, PointerMotion.level(9))
        assertEquals(PointerMotion.speed(0, PointerMotion.FAST, true), PointerMotion.speed(0, 42, true), eps)
    }

    @Test fun touchGainFollowsTheLevel() {
        assertEquals(1.8f, PointerMotion.touchGain(PointerMotion.NORMAL), eps)
        assertEquals(1.08f, PointerMotion.touchGain(PointerMotion.SLOW), eps)
        assertEquals(2.88f, PointerMotion.touchGain(PointerMotion.FAST), eps)
    }
}
