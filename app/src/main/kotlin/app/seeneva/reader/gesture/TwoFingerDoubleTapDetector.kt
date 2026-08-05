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
import kotlin.math.abs

/**
 * Detects a two-finger double-tap: two quick two-finger taps with minimal movement.
 *
 * Pure gesture state machine with no view dependencies so it can be unit tested.
 * The caller feeds normalized events (action, pointer count, pointers centroid,
 * event time) and receives `true` from [onEvent] exactly when the double-tap fires.
 *
 * @param doubleTapTimeoutMs max time between the first tap end and the second tap start
 * @param tapMaxDurationMs max time between the two fingers landing and the tap end
 * @param movementSlopPx max centroid movement during a tap
 */
class TwoFingerDoubleTapDetector(
    private val doubleTapTimeoutMs: Long = 300L,
    private val tapMaxDurationMs: Long = 250L,
    private val movementSlopPx: Float = 50f
) {
    /**
     * Centroid X of the fired (second) tap
     */
    var firedTapX = 0f
        private set

    /**
     * Centroid Y of the fired (second) tap
     */
    var firedTapY = 0f
        private set

    /**
     * True from the second tap start until it ends.
     * The caller should consume (intercept) the events while this is true
     * so the underlying image view does not react to the second tap.
     */
    var isSecondTapInProgress = false
        private set

    private var firstTapActive = false
    private var firstTapDownTime = 0L
    private var firstTapDownX = 0f
    private var firstTapDownY = 0f
    private var firstTapMoved = false
    private var firstTapEndTime = -1L

    private var secondTapActive = false
    private var secondTapDownTime = 0L
    private var secondTapDownX = 0f
    private var secondTapDownY = 0f
    private var secondTapMoved = false

    /**
     * Feed a normalized touch event.
     *
     * @param action [MotionEvent.getActionMasked]
     * @param pointerCount current pointer count
     * @param centroidX average X of all pointers
     * @param centroidY average Y of all pointers
     * @param eventTime event time in ms
     * @return true when a two-finger double-tap has been fired
     */
    fun onEvent(
        action: Int,
        pointerCount: Int,
        centroidX: Float,
        centroidY: Float,
        eventTime: Long
    ): Boolean {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                //A single finger went down - a potential start of any gesture
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerCount == 2) {
                    beginTap(centroidX, centroidY, eventTime)
                } else {
                    //Third finger - not a two-finger tap
                    reset()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (firstTapActive && moved(firstTapDownX, firstTapDownY, centroidX, centroidY)) {
                    firstTapMoved = true
                }

                if (secondTapActive &&
                    moved(secondTapDownX, secondTapDownY, centroidX, centroidY)
                ) {
                    secondTapMoved = true
                }
            }

            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                if (pointerCount <= 1) {
                    if (secondTapActive) {
                        isSecondTapInProgress = false

                        val duration = eventTime - secondTapDownTime

                        if (!secondTapMoved && duration <= tapMaxDurationMs) {
                            firedTapX = secondTapDownX
                            firedTapY = secondTapDownY

                            reset()

                            return true
                        }

                        reset()
                    } else if (firstTapActive) {
                        val duration = eventTime - firstTapDownTime

                        if (!firstTapMoved && duration <= tapMaxDurationMs) {
                            firstTapEndTime = eventTime
                        } else {
                            reset()
                        }

                        firstTapActive = false
                    }
                }
            }
        }

        return false
    }

    private fun beginTap(x: Float, y: Float, time: Long) {
        when {
            firstTapActive -> reset()

            firstTapEndTime < 0 -> {
                firstTapActive = true
                firstTapDownTime = time
                firstTapDownX = x
                firstTapDownY = y
                firstTapMoved = false
            }

            time - firstTapEndTime <= doubleTapTimeoutMs -> {
                secondTapActive = true
                isSecondTapInProgress = true
                secondTapDownTime = time
                secondTapDownX = x
                secondTapDownY = y
                secondTapMoved = false
            }

            else -> {
                //Double-tap window expired - this is a new first tap
                reset()

                firstTapActive = true
                firstTapDownTime = time
                firstTapDownX = x
                firstTapDownY = y
                firstTapMoved = false
            }
        }
    }

    private fun moved(downX: Float, downY: Float, x: Float, y: Float): Boolean =
        abs(x - downX) > movementSlopPx || abs(y - downY) > movementSlopPx

    private fun reset() {
        firstTapActive = false
        firstTapEndTime = -1
        secondTapActive = false
        isSecondTapInProgress = false
    }
}
