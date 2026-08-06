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

import android.graphics.RectF
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoFingerRectangleSelectorTest {
    private val selector = TwoFingerRectangleSelector()

    private fun twoFingersDown(x0: Float, y0: Float, x1: Float, y1: Float) {
        selector.onEvent(MotionEvent.ACTION_DOWN, 1, x0, y0, x0, y0)
        selector.onEvent(MotionEvent.ACTION_POINTER_DOWN, 2, x0, y0, x1, y1)
    }

    private fun move(x0: Float, y0: Float, x1: Float, y1: Float) {
        selector.onEvent(MotionEvent.ACTION_MOVE, 2, x0, y0, x1, y1)
    }

    /**
     * Lift the second finger (the selection completes on this event)
     * @return true when a zoom request was produced
     */
    private fun liftSecondFinger(x: Float, y: Float): Boolean =
        selector.onEvent(MotionEvent.ACTION_POINTER_UP, 1, x, y, x, y)

    /**
     * Lift the last finger
     */
    private fun liftLastFinger(x: Float, y: Float) {
        selector.onEvent(MotionEvent.ACTION_UP, 1, x, y, x, y)
    }

    // ---------- Pass-through cases ----------

    @Test
    fun pinchBeforeHoldIsPinch() {
        twoFingersDown(100f, 300f, 200f, 300f)

        //Span movement beyond the slop before the hold completes
        move(50f, 300f, 250f, 300f)

        assertFalse(selector.isSelecting)
        assertFalse(selector.isHoldPending)
        assertFalse(selector.previewVisible)

        //No zoom request on release
        assertFalse(liftSecondFinger(250f, 300f))
    }

    @Test
    fun twoFingerDragBeforeHoldIsPinch() {
        twoFingersDown(100f, 200f, 300f, 400f)

        //Centroid movement beyond the slop before the hold completes
        move(150f, 250f, 350f, 450f)

        assertFalse(selector.isSelecting)
        assertFalse(selector.previewVisible)
    }

    @Test
    fun quickTwoFingerTapIgnored() {
        twoFingersDown(100f, 200f, 300f, 400f)

        //Fingers lifted before the hold completed
        assertFalse(liftSecondFinger(300f, 400f))
        liftLastFinger(300f, 400f)

        assertFalse(selector.isSelecting)
        assertFalse(selector.isHoldPending)
        assertFalse(selector.previewVisible)
    }

    @Test
    fun movementBeforeHoldShowsNoPreview() {
        twoFingersDown(100f, 200f, 300f, 400f)

        //Movement below the slop: still within the hold window, no preview
        move(105f, 205f, 295f, 395f)

        assertTrue(selector.isHoldPending)
        assertFalse(selector.previewVisible)

        //Movement beyond the slop: normal pinch
        move(150f, 250f, 250f, 350f)

        assertFalse(selector.isHoldPending)
        assertFalse(selector.previewVisible)
    }

    // ---------- Rectangle cases ----------

    @Test
    fun holdActivatesSelection() {
        twoFingersDown(100f, 200f, 300f, 400f)

        //No preview during the hold waiting period
        assertTrue(selector.isHoldPending)
        assertFalse(selector.previewVisible)

        selector.enterSelecting()

        assertTrue(selector.isSelecting)
        assertTrue(selector.previewVisible)
        assertEquals(RectF(100f, 200f, 300f, 400f), selector.previewRect)
    }

    @Test
    fun afterHoldDragRectangleFollowsFingers() {
        twoFingersDown(100f, 200f, 300f, 400f)
        selector.enterSelecting()

        move(150f, 250f, 350f, 450f)

        assertTrue(selector.isSelecting)
        assertEquals(RectF(150f, 250f, 350f, 450f), selector.previewRect)
    }

    @Test
    fun releaseAfterSelectionZooms() {
        twoFingersDown(100f, 200f, 300f, 400f)
        selector.enterSelecting()
        move(150f, 250f, 350f, 450f)

        assertTrue(liftSecondFinger(350f, 450f))
        assertEquals(RectF(150f, 250f, 350f, 450f), selector.completedRect)
    }

    @Test
    fun holdWithSmallJitterStillActivates() {
        twoFingersDown(100f, 200f, 300f, 400f)

        //Small jitter within the slop
        move(105f, 205f, 295f, 395f)
        move(100f, 200f, 300f, 400f)

        assertTrue(selector.isHoldPending)

        selector.enterSelecting()

        assertTrue(selector.isSelecting)
        assertTrue(selector.previewVisible)
    }

    @Test
    fun thirdFingerCancels() {
        twoFingersDown(100f, 200f, 300f, 400f)

        selector.onEvent(MotionEvent.ACTION_POINTER_DOWN, 3, 200f, 300f, 300f, 400f)

        assertFalse(selector.isHoldPending)
        assertFalse(selector.isSelecting)
        assertFalse(selector.previewVisible)

        //A late activation does nothing
        selector.enterSelecting()

        assertFalse(selector.isSelecting)
    }

    @Test
    fun actionCancelCancels() {
        twoFingersDown(100f, 200f, 300f, 400f)
        selector.enterSelecting()

        assertTrue(selector.isSelecting)

        selector.onEvent(MotionEvent.ACTION_CANCEL, 2, 100f, 200f, 300f, 400f)

        assertFalse(selector.isSelecting)
        assertFalse(selector.previewVisible)
    }

    @Test
    fun consumesAllEventsAfterActivation() {
        twoFingersDown(100f, 200f, 300f, 400f)
        selector.enterSelecting()

        move(150f, 250f, 350f, 450f)

        assertTrue(selector.isSelecting)

        assertTrue(liftSecondFinger(350f, 450f))

        //Still consuming the remaining finger (including its moves)
        assertTrue(selector.isSelecting)
        selector.onEvent(MotionEvent.ACTION_MOVE, 1, 360f, 460f, 360f, 460f)
        assertTrue(selector.isSelecting)

        //The last finger up does not complete again and resets the selector
        assertFalse(selector.onEvent(MotionEvent.ACTION_UP, 1, 360f, 460f, 360f, 460f))
        assertFalse(selector.isSelecting)
    }

    @Test
    fun tinySelectionDoesNotProduceZoomRequest() {
        twoFingersDown(100f, 200f, 130f, 230f)
        selector.enterSelecting()

        //Select but keep the rectangle smaller than the minimum size
        move(150f, 250f, 180f, 280f)

        assertTrue(selector.isSelecting)

        assertFalse(liftSecondFinger(180f, 280f))
    }

    @Test
    fun oneFingerTapDoesNothing() {
        selector.onEvent(MotionEvent.ACTION_DOWN, 1, 100f, 200f, 100f, 200f)
        selector.onEvent(MotionEvent.ACTION_UP, 1, 100f, 200f, 100f, 200f)

        assertFalse(selector.isHoldPending)
        assertFalse(selector.isSelecting)
        assertFalse(selector.previewVisible)

        //A late activation does nothing
        selector.enterSelecting()

        assertFalse(selector.isSelecting)
    }

    // ---------- Stillness window (B1) ----------

    @Test
    fun stillHoldHasNoMovementSinceHoldStart() {
        twoFingersDown(100f, 200f, 300f, 400f)

        //Perfectly still and tiny jitter below the stillness slop
        assertFalse(selector.movedSinceHoldStart(100f, 200f, 300f, 400f))
        assertFalse(selector.movedSinceHoldStart(101f, 201f, 299f, 399f))
    }

    @Test
    fun spanDriftBeyondStillnessRestartsWindow() {
        twoFingersDown(100f, 200f, 300f, 400f)

        //A slow pinch drifts the span but not the centroid
        assertTrue(selector.movedSinceHoldStart(110f, 200f, 290f, 400f))
    }

    @Test
    fun centroidDriftBeyondStillnessRestartsWindow() {
        twoFingersDown(100f, 200f, 300f, 400f)

        //A two-finger drag drifts the centroid but not the span
        assertTrue(selector.movedSinceHoldStart(150f, 250f, 350f, 450f))
    }

    @Test
    fun smallSettlingMovementStillActivates() {
        twoFingersDown(100f, 200f, 300f, 400f)

        //Small settling movement below the stillness slop does not block activation
        assertFalse(selector.movedSinceHoldStart(102f, 201f, 298f, 399f))

        selector.enterSelecting()

        assertTrue(selector.isSelecting)
    }

    @Test
    fun movedSinceHoldStartIgnoredOutsideHoldPending() {
        twoFingersDown(100f, 200f, 300f, 400f)
        selector.enterSelecting()

        //No longer in HOLD_PENDING: no stillness signal
        assertFalse(selector.movedSinceHoldStart(150f, 250f, 350f, 450f))
    }

    @Test
    fun completionOnFinalUpNeverStaysStuck() {
        twoFingersDown(100f, 200f, 300f, 400f)
        selector.enterSelecting()
        move(150f, 250f, 350f, 450f)

        //A caller that passes the raw ACTION_POINTER_UP count (2) skips the
        //completion at the first lift; the completion then lands on the final
        //ACTION_UP (remaining count 1). The selector must complete AND reset,
        //never staying stuck in SELECTING.
        val request = selector.onEvent(MotionEvent.ACTION_UP, 1, 350f, 450f, 350f, 450f)

        assertTrue(request)
        assertFalse(selector.isSelecting)
        assertFalse(selector.isHoldPending)
        assertFalse(selector.previewVisible)
        assertEquals(RectF(150f, 250f, 350f, 450f), selector.completedRect)
    }
}
