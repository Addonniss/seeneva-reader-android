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

package app.seeneva.reader.logic.entity

import android.graphics.RectF
import app.seeneva.reader.logic.entity.ml.ObjectClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests of the direct (spatial) bubble selection rule.
 *
 * `android.graphics.RectF` constructors are stubbed in the local unit test
 * environment (mockable android.jar), so [rectF] allocates an instance without
 * calling the constructor and sets its public fields directly. This allows the
 * real R-Tree infrastructure to be exercised on a plain JVM.
 */
class ComicPageObjectContainerTest {

    @Test
    fun tapInsideBubbleASelectsA() {
        val container = container(
            objectAt(1, left = 0f, top = 0f, right = 100f, bottom = 50f),
            objectAt(2, left = 200f, top = 0f, right = 300f, bottom = 50f),
        )

        //Point inside bubble A
        assertEquals(0, container.indexOf(50f, 25f))
    }

    @Test
    fun tapInsideBubbleBSelectsB() {
        val container = container(
            objectAt(1, left = 0f, top = 0f, right = 100f, bottom = 50f),
            objectAt(2, left = 200f, top = 0f, right = 300f, bottom = 50f),
        )

        //Point inside bubble B
        assertEquals(1, container.indexOf(250f, 25f))
    }

    @Test
    fun tapOutsideAllBubblesDoesNotSelectAny() {
        val container = container(
            objectAt(1, left = 0f, top = 0f, right = 100f, bottom = 50f),
            objectAt(2, left = 200f, top = 0f, right = 300f, bottom = 50f),
        )

        //Point in the gap between bubbles A and B
        assertNull(container.indexOf(150f, 25f))
    }

    @Test
    fun centerLocatedBubbleIsSelectable() {
        //A bubble located in the center of the page must be found by the spatial
        //query (the center region was previously intercepted by the hide-zone
        //check in the UI layer)
        val container = container(
            objectAt(1, left = 40f, top = 0f, right = 160f, bottom = 50f),
        )

        //Point in the middle of the page, inside the bubble
        assertEquals(0, container.indexOf(100f, 25f))
    }

    @Test
    fun overlappingBubblesFollowDeterministicRTreeRule() {
        //Bubbles A and B overlap in the X range 50..100
        val container = container(
            objectAt(1, left = 0f, top = 0f, right = 100f, bottom = 50f),
            objectAt(2, left = 50f, top = 0f, right = 150f, bottom = 50f),
        )

        val overlapX = 75f
        val overlapY = 25f

        val selected = container.indexOf(overlapX, overlapY)

        //Exactly one of the overlapping objects is selected
        assertNotNull(selected)
        assertTrue(selected == 0 || selected == 1)

        //The rule is deterministic: repeated queries return the same object
        repeat(5) {
            assertEquals(selected, container.indexOf(overlapX, overlapY))
        }

        //The rule is consistent with the existing R-Tree search order
        assertEquals(
            container[selected],
            container[overlapX, overlapY].firstOrNull()
        )
    }

    private fun container(vararg objects: ComicPageObject) =
        ComicPageObjectContainer(objects.toList(), Direction.LTR)

    private fun objectAt(
        id: Long,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ) = ComicPageObject(id, ObjectClass.SPEECH_BALLOON, rectF(left, top, right, bottom))

    private fun rectF(left: Float, top: Float, right: Float, bottom: Float): RectF {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe")
            .apply { isAccessible = true }
            .get(null)
        val allocator = unsafeClass.getMethod("allocateInstance", Class::class.java)

        return (allocator.invoke(unsafe, RectF::class.java) as RectF).apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }
    }
}
