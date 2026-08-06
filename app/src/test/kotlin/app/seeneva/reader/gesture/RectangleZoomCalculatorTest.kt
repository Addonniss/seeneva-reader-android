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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RectangleZoomCalculatorTest {
    //View 1000x1400 showing a 1000x1400 source image at scale 1.0 (fit),
    //viewport center in source coordinates = (500, 700)
    private fun calculate(
        selection: RectF,
        currentScale: Float = 1.0f,
        centerSourceX: Float = 500f,
        centerSourceY: Float = 700f,
        minScale: Float = 1.0f,
        maxScale: Float = 10f
    ) = RectangleZoomCalculator.calculate(
        selection = selection,
        viewWidth = 1000f,
        viewHeight = 1400f,
        sourceWidth = 1000f,
        sourceHeight = 1400f,
        currentScale = currentScale,
        centerSourceX = centerSourceX,
        centerSourceY = centerSourceY,
        minScale = minScale,
        maxScale = maxScale
    )

    @Test
    fun leftHalfZoomsViaStripFallback() {
        //The left half spans the full viewport height: a strip, FILL zoom
        val target = calculate(RectF(0f, 0f, 500f, 1400f))

        assertEquals(
            RectangleZoomCalculator.ZoomTarget(scale = 2.0f, centerX = 250f, centerY = 700f),
            target
        )
    }

    @Test
    fun rightHalfZoomsViaStripFallback() {
        //The right half spans the full viewport height: a strip, FILL zoom
        val target = calculate(RectF(500f, 0f, 1000f, 1400f))

        assertEquals(
            RectangleZoomCalculator.ZoomTarget(scale = 2.0f, centerX = 750f, centerY = 700f),
            target
        )
    }

    @Test
    fun topBandZoomsViaStripFallback() {
        //A full-width band spans the whole viewport width: a strip, FILL zoom
        val target = calculate(RectF(0f, 0f, 1000f, 400f))

        assertEquals(
            RectangleZoomCalculator.ZoomTarget(scale = 3.5f, centerX = 500f, centerY = 200f),
            target
        )
    }

    @Test
    fun tallColumnZoomsViaStripFallback() {
        //A tall column spans the full viewport height: a strip, FILL zoom
        val target = calculate(RectF(400f, 0f, 600f, 1400f))

        assertEquals(
            RectangleZoomCalculator.ZoomTarget(scale = 5.0f, centerX = 500f, centerY = 700f),
            target
        )
    }

    @Test
    fun widePanelFitsCompletely() {
        //A wide panel is not a strip: FIT, the whole panel visible
        val target = calculate(RectF(200f, 525f, 900f, 875f))

        assertEquals(1.4286f, target!!.scale, 0.01f)
        assertEquals(550f, target.centerX, 0.01f)
        assertEquals(700f, target.centerY, 0.01f)
    }

    @Test
    fun portraitPanelFitsCompletely() {
        //A portrait panel is not a strip: FIT, the whole panel visible
        val target = calculate(RectF(300f, 250f, 700f, 1150f))

        assertEquals(1.5556f, target!!.scale, 0.01f)
        assertEquals(500f, target.centerX, 0.01f)
        assertEquals(700f, target.centerY, 0.01f)
    }

    @Test
    fun largePanelUsesFitNotCrop() {
        //A large panel at ~85% of the viewport width is still a panel: FIT is
        //used (the whole panel visible) instead of FILL (which would crop)
        val target = calculate(RectF(75f, 450f, 925f, 950f))

        //Fit of the 850x500 selection = min(1000/850, 1400/500) = 1.176
        assertEquals(1.1765f, target!!.scale, 0.01f)
        assertEquals(500f, target.centerX, 0.01f)
        assertEquals(700f, target.centerY, 0.01f)
    }

    @Test
    fun stripBoundaryThreshold() {
        //88% width: not a strip -> FIT, which is below the zoom gate -> no-op
        assertNull(calculate(RectF(60f, 0f, 940f, 700f)))

        //96% width: a strip -> FILL zoom
        val strip = calculate(RectF(20f, 0f, 980f, 700f))
        assertEquals(2.0f, strip!!.scale, 0.01f)
    }

    @Test
    fun centerQuarterFillsViewport() {
        val target = calculate(RectF(250f, 350f, 750f, 1050f))

        //Source rect 500x700 -> fill = max(1000/500, 1400/700) = 2.0
        assertEquals(
            RectangleZoomCalculator.ZoomTarget(scale = 2.0f, centerX = 500f, centerY = 700f),
            target
        )
    }

    @Test
    fun selectionClampedToImageBounds() {
        //Selection partially outside the image (letterbox margin)
        val target = calculate(RectF(-200f, -200f, 300f, 600f))

        //Clamped source rect 300x600 -> fit = min(1000/300, 1400/600) = 2.333
        assertEquals(2.333f, target!!.scale, 0.01f)
        assertEquals(150f, target.centerX, 0.01f)
        assertEquals(300f, target.centerY, 0.01f)
    }

    @Test
    fun selectionFullyOutsideImageIsIgnored() {
        assertNull(calculate(RectF(-500f, -500f, -100f, -100f)))
    }

    @Test
    fun insignificantZoomIsIgnored() {
        //Selection covering almost the whole viewport - fill barely exceeds 1.0
        assertNull(calculate(RectF(0f, 0f, 950f, 1330f)))
    }

    @Test
    fun tinySelectionClampedToMaxScale() {
        val target = calculate(RectF(400f, 600f, 450f, 650f))

        //Fill would be 28x, clamped to maxScale 10
        assertEquals(10f, target!!.scale, 0.01f)
        assertEquals(425f, target.centerX, 0.01f)
        assertEquals(625f, target.centerY, 0.01f)
    }

    @Test
    fun worksFromZoomedInState() {
        //Zoomed to 4x on the image center: viewport center source = (500, 700)
        val target = calculate(
            selection = RectF(250f, 350f, 750f, 1050f),
            currentScale = 4.0f
        )

        //Source rect 125x175 -> fill = max(1000/125, 1400/175) = 8.0
        assertEquals(
            RectangleZoomCalculator.ZoomTarget(scale = 8.0f, centerX = 500f, centerY = 700f),
            target
        )
    }

    @Test
    fun neverZoomsOut() {
        //Zoomed to 8x; selecting the whole visible viewport would not increase the zoom
        assertNull(
            calculate(
                selection = RectF(0f, 0f, 1000f, 1400f),
                currentScale = 8.0f
            )
        )
    }
}
