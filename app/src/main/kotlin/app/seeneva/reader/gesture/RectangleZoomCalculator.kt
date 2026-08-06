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
     * Panels are FIT: the whole selected rectangle becomes visible and the
     * longer edge governs the scale (margins may appear). Selections spanning
     * almost the whole viewport in one dimension (page halves, bands, tall
     * columns) are treated as strips and FILL the viewport so they still zoom;
     * the user pans along the strip. The result is clamped to the library scale
     * limits and zoom requests which would barely change the current zoom are
     * ignored.
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
     * @param stripCoverageThreshold a selection spanning at least this fraction
     * of the viewport in one dimension is a strip and zooms FILL style; all
     * other selections FIT
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
        minZoomIncrease: Float = 1.15f,
        stripCoverageThreshold: Float = 0.95f
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

        //Geometry: how much of the viewport does the drawn selection span?
        //A selection spanning almost the whole viewport in one dimension is a
        //strip (page half, band, tall column) and still zooms; other selections
        //are panels which should fit completely
        val viewLeft = max(0f, min(viewWidth, selection.left))
        val viewRight = max(0f, min(viewWidth, selection.right))
        val viewTop = max(0f, min(viewHeight, selection.top))
        val viewBottom = max(0f, min(viewHeight, selection.bottom))

        val coverageX = (viewRight - viewLeft) / viewWidth
        val coverageY = (viewBottom - viewTop) / viewHeight

        val isStrip = max(coverageX, coverageY) >= stripCoverageThreshold

        //Panels FIT (the whole selection visible, the longer edge governs);
        //strips FILL (the short edge fills the viewport, the user pans along it)
        var targetScale = if (isStrip) {
            max(viewWidth / selectedWidth, viewHeight / selectedHeight)
        } else {
            min(viewWidth / selectedWidth, viewHeight / selectedHeight)
        }

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
