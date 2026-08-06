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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Detects a two-finger rectangular area zoom selection.
 *
 * Pure gesture state machine with no view dependencies so it can be unit tested.
 *
 * The gesture is a deliberate hold: two fingers must stay mostly still for the
 * hold duration, then rectangle selection activates (the caller starts the hold
 * timer and calls [enterSelecting]). Movement beyond the slop before the hold
 * completes is treated as a normal pinch and the gesture is passed through to
 * the image view.
 *
 * @param slopPx touch slop: movement beyond it before the hold completes cancels
 * the rectangle gesture (normal pinch)
 * @param minSelectionPx minimum selection size (view px) to produce a zoom request
 */
class TwoFingerRectangleSelector(
    private val slopPx: Float = 24f,
    private val minSelectionPx: Float = 48f
) {
    private enum class State { IDLE, TRACKING, HOLD_PENDING, SELECTING, PINCH }

    private var state = State.IDLE

    private var startSpan = 0f
    private var startCentroidX = 0f
    private var startCentroidY = 0f

    //last known two-pointer positions (view coordinates)
    private var lastX0 = 0f
    private var lastY0 = 0f
    private var lastX1 = 0f
    private var lastY1 = 0f

    private var selectionCompleted = false

    /**
     * Current rectangle preview (view coordinates). Valid when [previewVisible]
     */
    val previewRect = RectF()

    /**
     * Completed selection (view coordinates). Valid when [onEvent] returns true
     */
    val completedRect = RectF()

    /**
     * True while the two-finger hold is pending (before [enterSelecting] or a
     * pinch decision)
     */
    var isHoldPending = false
        private set

    /**
     * True while a rectangle selection is active and the caller should consume
     * (not forward) the touch events so the image view cannot zoom/pan
     */
    var isSelecting = false
        private set

    /**
     * True while a rectangle preview should be drawn
     */
    var previewVisible = false
        private set

    /**
     * Activate the rectangle selection after the hold duration.
     * Does nothing if the hold was cancelled by movement (pinch), finger lift or
     * a third finger.
     */
    fun enterSelecting() {
        if (state == State.HOLD_PENDING) {
            state = State.SELECTING
            isHoldPending = false
            isSelecting = true
            previewVisible = true

            updatePreview(lastX0, lastY0, lastX1, lastY1)
        }
    }

    /**
     * Feed a touch event.
     *
     * @param action [MotionEvent.getActionMasked]
     * @param pointerCount current pointer count
     * @param x0/y0 first pointer position
     * @param x1/y1 second pointer position (valid when pointerCount >= 2)
     * @return true when the selection completed on this event (zoom request)
     */
    fun onEvent(
        action: Int,
        pointerCount: Int,
        x0: Float, y0: Float,
        x1: Float, y1: Float
    ): Boolean {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (state == State.IDLE) {
                    state = State.TRACKING
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                when {
                    state == State.TRACKING && pointerCount == 2 -> {
                        //Two fingers down: the hold window starts. No preview yet.
                        //The caller starts the hold timer which calls [enterSelecting]
                        startSpan = span(x0, y0, x1, y1)
                        startCentroidX = (x0 + x1) * 0.5f
                        startCentroidY = (y0 + y1) * 0.5f

                        updatePreview(x0, y0, x1, y1)

                        state = State.HOLD_PENDING
                        isHoldPending = true
                    }

                    pointerCount >= 3 -> reset()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                when (state) {
                    State.HOLD_PENDING -> {
                        val spanDelta = abs(span(x0, y0, x1, y1) - startSpan)
                        val centroidDelta = distance(
                            startCentroidX,
                            startCentroidY,
                            (x0 + x1) * 0.5f,
                            (y0 + y1) * 0.5f
                        )

                        if (spanDelta > slopPx || centroidDelta > slopPx) {
                            //Movement before the hold completed - a normal pinch.
                            //The gesture is passed through to the image view.
                            state = State.PINCH
                            isHoldPending = false
                        } else {
                            //Still within the hold window (small jitter)
                            updatePreview(x0, y0, x1, y1)
                        }
                    }

                    State.SELECTING -> {
                        if (pointerCount >= 2) {
                            updatePreview(x0, y0, x1, y1)
                        }
                    }

                    else -> Unit
                }
            }

            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                when (state) {
                    State.HOLD_PENDING -> {
                        when {
                            action == MotionEvent.ACTION_UP -> reset()
                            pointerCount <= 1 -> {
                                //Fingers lifted before the hold completed - not a selection
                                state = State.TRACKING
                                isHoldPending = false
                            }
                        }
                    }

                    State.SELECTING -> {
                        if (pointerCount <= 1 && !selectionCompleted) {
                            //First finger lifted - complete the selection.
                            //Keep consuming until the last finger is lifted.
                            selectionCompleted = true

                            completedRect.set(
                                previewRect.left,
                                previewRect.top,
                                previewRect.right,
                                previewRect.bottom
                            )

                            previewVisible = false

                            return completedRect.width() >= minSelectionPx &&
                                completedRect.height() >= minSelectionPx
                        }

                        if (action == MotionEvent.ACTION_UP) {
                            //The gesture fully ended
                            reset()
                        }
                    }

                    State.PINCH, State.TRACKING -> {
                        if (action == MotionEvent.ACTION_UP) {
                            reset()
                        }
                    }

                    State.IDLE -> Unit
                }
            }

            MotionEvent.ACTION_CANCEL -> reset()
        }

        return false
    }

    /**
     * Reset the selector to the idle state
     */
    fun reset() {
        state = State.IDLE
        isHoldPending = false
        selectionCompleted = false
        isSelecting = false
        previewVisible = false
    }

    private fun updatePreview(x0: Float, y0: Float, x1: Float, y1: Float) {
        lastX0 = x0
        lastY0 = y0
        lastX1 = x1
        lastY1 = y1

        previewRect.set(
            min(x0, x1),
            min(y0, y1),
            max(x0, x1),
            max(y0, y1)
        )
    }

    private fun span(x0: Float, y0: Float, x1: Float, y1: Float): Float =
        distance(x0, y0, x1, y1)

    private fun distance(x0: Float, y0: Float, x1: Float, y1: Float): Float =
        sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
}
