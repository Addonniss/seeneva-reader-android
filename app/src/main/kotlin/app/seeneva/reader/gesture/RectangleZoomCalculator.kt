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
import kotlin.math.max
import kotlin.math.min

/**
 * Pure math for the two-finger rectangular area zoom.
 */
object RectangleZoomCalculator {

    /**
     * Target zoom: the scale and the source point which should become the
     * viewport center
     */
    data class ZoomTarget(
        val scale: Float,
        val centerX: Float,
        val centerY: Float
    )

    /**
     * Calculate the zoom target for a selection rectangle.
     *
     * The selected rectangle fills the viewport (cropping is accepted). The
     * result is clamped to the library scale limits and zoom requests which
     * would barely change the current zoom are ignored.
     *
     * @param selection selection rectangle in view coordinates
     * @param viewWidth/viewHeight the image view size (view px)
     * @param sourceWidth/sourceHeight the image size (source px)
     * @param currentScale current zoom scale
     * @param centerSourceX/centerSourceY source coordinates of the current
     * viewport center (as returned by [com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.getCenter])
     * @param minScale/maxScale library scale limits
     * @param minZoomIncrease ignore zooms which increase the scale by less than
     * this factor
     * @return the zoom target or null when the selection should be ignored
     */
    fun calculate(
        selection: RectF,
        viewWidth: Float,
        viewHeight: Float,
        sourceWidth: Float,
        sourceHeight: Float,
        currentScale: Float,
        centerSourceX: Float,
        centerSourceY: Float,
        minScale: Float,
        maxScale: Float,
        minZoomIncrease: Float = 1.15f
    ): ZoomTarget? {
        //View -> source conversion for the current transform:
        //source = centerSource + (view - viewCenter) / scale
        val halfViewWidth = viewWidth * 0.5f
        val halfViewHeight = viewHeight * 0.5f

        val sourceLeft = centerSourceX + (selection.left - halfViewWidth) / currentScale
        val sourceTop = centerSourceY + (selection.top - halfViewHeight) / currentScale
        val sourceRight = centerSourceX + (selection.right - halfViewWidth) / currentScale
        val sourceBottom = centerSourceY + (selection.bottom - halfViewHeight) / currentScale

        //Clamp the selection to the image bounds
        val clampedLeft = max(0f, sourceLeft)
        val clampedTop = max(0f, sourceTop)
        val clampedRight = min(sourceWidth, sourceRight)
        val clampedBottom = min(sourceHeight, sourceBottom)

        val selectedWidth = clampedRight - clampedLeft
        val selectedHeight = clampedBottom - clampedTop

        //Selection fully outside the image
        if (selectedWidth <= 0f || selectedHeight <= 0f) {
            return null
        }

        //FILL: the selected rectangle becomes the viewport
        var targetScale = max(
            viewWidth / selectedWidth,
            viewHeight / selectedHeight
        )

        //Respect the zoom limits
        targetScale = min(maxScale, max(minScale, targetScale))

        //Ignore insignificant zooms (and never zoom out)
        if (targetScale / currentScale <= minZoomIncrease) {
            return null
        }

        return ZoomTarget(
            scale = targetScale,
            centerX = (clampedLeft + clampedRight) * 0.5f,
            centerY = (clampedTop + clampedBottom) * 0.5f
        )
    }
}
