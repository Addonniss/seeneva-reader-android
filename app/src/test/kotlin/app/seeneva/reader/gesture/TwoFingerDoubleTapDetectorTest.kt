/*
 * This file is part of Seeneva Android Reader
 * Copyright (C) 2021 Sergei Solodovnikov
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package app.seeneva.reader.gesture

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoFingerDoubleTapDetectorTest {
    private val detector = TwoFingerDoubleTapDetector()

    /**
     * Simulate one two-finger tap starting at [startTime].
     * @return the time the tap ended
     */
    private fun twoFingerTap(x: Float, y: Float, startTime: Long): Long {
        //First finger down
        detector.onEvent(MotionEvent.ACTION_DOWN, 1, x, y, startTime)
        //Second finger down - tap active
        detector.onEvent(MotionEvent.ACTION_POINTER_DOWN, 2, x, y, startTime + 20)
        //First finger up
        detector.onEvent(MotionEvent.ACTION_POINTER_UP, 1, x, y, startTime + 60)
        //Last finger up - tap completed
        detector.onEvent(MotionEvent.ACTION_UP, 1, x, y, startTime + 90)

        return startTime + 90
    }

    /**
     * Simulate the second two-finger tap of a double-tap.
     * @return true when the double-tap fired (reported on the first finger up)
     */
    private fun secondTwoFingerTap(x: Float, y: Float, startTime: Long): Boolean {
        detector.onEvent(MotionEvent.ACTION_DOWN, 1, x, y, startTime)
        detector.onEvent(MotionEvent.ACTION_POINTER_DOWN, 2, x, y, startTime + 20)

        val fired = detector.onEvent(MotionEvent.ACTION_POINTER_UP, 1, x, y, startTime + 60)

        detector.onEvent(MotionEvent.ACTION_UP, 1, x, y, startTime + 90)

        return fired
    }

    @Test
    fun validTwoFingerDoubleTapFires() {
        twoFingerTap(500f, 300f, 0L)

        val fired = secondTwoFingerTap(480f, 320f, 200L)

        assertTrue(fired)
        assertEquals(480f, detector.firedTapX, 0.01f)
        assertEquals(320f, detector.firedTapY, 0.01f)
    }

    @Test
    fun singleFingerTapDoesNotFire() {
        //Only one-finger taps
        detector.onEvent(MotionEvent.ACTION_DOWN, 1, 500f, 300f, 0L)
        detector.onEvent(MotionEvent.ACTION_UP, 1, 500f, 300f, 80L)

        detector.onEvent(MotionEvent.ACTION_DOWN, 1, 510f, 310f, 200L)
        detector.onEvent(MotionEvent.ACTION_UP, 1, 510f, 310f, 280L)

        assertFalse(detector.isSecondTapInProgress)
    }

    @Test
    fun singleTwoFingerTapDoesNotFire() {
        //One two-finger tap only is not a double-tap
        twoFingerTap(500f, 300f, 0L)

        assertFalse(detector.isSecondTapInProgress)
    }

    @Test
    fun movementBeyondSlopDoesNotFire() {
        twoFingerTap(500f, 300f, 0L)

        //Second tap with movement beyond the slop (pinch-like)
        detector.onEvent(MotionEvent.ACTION_DOWN, 1, 500f, 300f, 200L)
        detector.onEvent(MotionEvent.ACTION_POINTER_DOWN, 2, 500f, 300f, 220L)
        detector.onEvent(MotionEvent.ACTION_MOVE, 2, 600f, 400f, 240L)
        detector.onEvent(MotionEvent.ACTION_POINTER_UP, 1, 600f, 400f, 260L)
        detector.onEvent(MotionEvent.ACTION_UP, 1, 600f, 400f, 290L)

        assertFalse(detector.isSecondTapInProgress)
    }

    @Test
    fun slowSecondTapDoesNotFire() {
        twoFingerTap(500f, 300f, 0L)

        //Second tap starts after the double-tap window expired
        twoFingerTap(480f, 320f, 1000L)

        assertFalse(detector.isSecondTapInProgress)
    }

    @Test
    fun pinchLikeMovementDoesNotFire() {
        //First gesture is a pinch (two fingers with movement) - not a tap
        detector.onEvent(MotionEvent.ACTION_DOWN, 1, 400f, 400f, 0L)
        detector.onEvent(MotionEvent.ACTION_POINTER_DOWN, 2, 600f, 400f, 20L)
        detector.onEvent(MotionEvent.ACTION_MOVE, 2, 500f, 420f, 40L)
        detector.onEvent(MotionEvent.ACTION_MOVE, 2, 520f, 430f, 60L)
        detector.onEvent(MotionEvent.ACTION_POINTER_UP, 1, 520f, 430f, 80L)
        detector.onEvent(MotionEvent.ACTION_UP, 1, 520f, 430f, 110L)

        //Then a clean two-finger tap - should be treated as a first tap, not a double-tap
        twoFingerTap(500f, 300f, 200L)

        assertFalse(detector.isSecondTapInProgress)
    }

    @Test
    fun incorrectPointerCountDoesNotFire() {
        //Three-finger tap
        detector.onEvent(MotionEvent.ACTION_DOWN, 1, 500f, 300f, 0L)
        detector.onEvent(MotionEvent.ACTION_POINTER_DOWN, 2, 520f, 300f, 20L)
        detector.onEvent(MotionEvent.ACTION_POINTER_DOWN, 3, 500f, 320f, 40L)
        detector.onEvent(MotionEvent.ACTION_POINTER_UP, 2, 520f, 300f, 60L)
        detector.onEvent(MotionEvent.ACTION_POINTER_UP, 1, 500f, 300f, 80L)
        detector.onEvent(MotionEvent.ACTION_UP, 1, 500f, 300f, 110L)

        assertFalse(detector.isSecondTapInProgress)
    }

    @Test
    fun secondTapInProgressDuringSecondTap() {
        twoFingerTap(500f, 300f, 0L)

        //Second tap starts
        detector.onEvent(MotionEvent.ACTION_DOWN, 1, 480f, 320f, 200L)
        detector.onEvent(MotionEvent.ACTION_POINTER_DOWN, 2, 480f, 320f, 220L)

        assertTrue(detector.isSecondTapInProgress)

        detector.onEvent(MotionEvent.ACTION_POINTER_UP, 1, 480f, 320f, 260L)
        detector.onEvent(MotionEvent.ACTION_UP, 1, 480f, 320f, 290L)

        assertFalse(detector.isSecondTapInProgress)
    }
}
