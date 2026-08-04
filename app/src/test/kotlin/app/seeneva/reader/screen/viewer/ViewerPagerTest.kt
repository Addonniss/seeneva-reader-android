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

package app.seeneva.reader.screen.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests of the instant page turn decision logic (pure part of [ViewerPager]).
 */
class ViewerPagerTest {

    @Test
    fun swipeLeftTurnsToNextPage() {
        assertEquals(
            1,
            ViewerPager.instantPageTurnTarget(
                currentItem = 0,
                itemCount = 10,
                dx = -300f,
                width = 1000,
                thresholdFraction = 0.25f
            )
        )
    }

    @Test
    fun swipeRightTurnsToPreviousPage() {
        assertEquals(
            0,
            ViewerPager.instantPageTurnTarget(
                currentItem = 1,
                itemCount = 10,
                dx = 300f,
                width = 1000,
                thresholdFraction = 0.25f
            )
        )
    }

    @Test
    fun shortSwipeDoesNotTurnPage() {
        assertNull(
            ViewerPager.instantPageTurnTarget(
                currentItem = 0,
                itemCount = 10,
                dx = -100f,
                width = 1000,
                thresholdFraction = 0.25f
            )
        )
    }

    @Test
    fun firstPageCannotTurnBack() {
        //Swipe right on the first page is clamped to the current page (no-op)
        assertEquals(
            0,
            ViewerPager.instantPageTurnTarget(
                currentItem = 0,
                itemCount = 10,
                dx = 300f,
                width = 1000,
                thresholdFraction = 0.25f
            )
        )
    }

    @Test
    fun lastPageCannotTurnForward() {
        //Swipe left on the last page is clamped to the current page (no-op)
        assertEquals(
            9,
            ViewerPager.instantPageTurnTarget(
                currentItem = 9,
                itemCount = 10,
                dx = -300f,
                width = 1000,
                thresholdFraction = 0.25f
            )
        )
    }

    @Test
    fun emptyPagerDoesNotTurnPage() {
        assertNull(
            ViewerPager.instantPageTurnTarget(
                currentItem = 0,
                itemCount = 0,
                dx = -300f,
                width = 1000,
                thresholdFraction = 0.25f
            )
        )
    }
}
