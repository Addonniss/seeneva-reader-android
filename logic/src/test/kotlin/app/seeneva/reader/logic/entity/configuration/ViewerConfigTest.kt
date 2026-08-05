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

package app.seeneva.reader.logic.entity.configuration

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViewerConfigTest {
    private val json = Json

    @Test
    fun instantViewerInteractionsDefaultsToFalse() {
        //Default value must preserve current Seeneva behavior (animated interactions)
        assertFalse(ViewerConfig().instantViewerInteractions)
    }

    @Test
    fun oldStoredConfigWithoutInstantFieldDecodesToFalse() {
        //Simulate a viewer config saved before "instant viewer interactions" setting existed
        val oldConfig = """{"keep_screen_on":true,"brightness":-1.0,"tts":true}"""

        val decoded = json.decodeFromString<ViewerConfig>(oldConfig)

        assertFalse(decoded.instantViewerInteractions)
        assertTrue(decoded.keepScreenOn)
    }

    @Test
    fun instantViewerInteractionsRoundTrip() {
        val config = ViewerConfig(instantViewerInteractions = true)

        val decoded = json.decodeFromString<ViewerConfig>(json.encodeToString(config))

        assertTrue(decoded.instantViewerInteractions)
    }

    @Test
    fun bubbleScaleDefaultsToOne() {
        //Default value must preserve current Seeneva behavior (bubble shown at on-page size)
        assertEquals(1.0f, ViewerConfig().bubbleScale)
    }

    @Test
    fun oldStoredConfigWithoutBubbleScaleDecodesToOne() {
        //Simulate a viewer config saved before "bubble size" setting existed
        val oldConfig = """{"keep_screen_on":true,"brightness":-1.0,"tts":true}"""

        val decoded = json.decodeFromString<ViewerConfig>(oldConfig)

        assertEquals(1.0f, decoded.bubbleScale)
    }

    @Test
    fun bubbleScaleRoundTrip() {
        val config = ViewerConfig(bubbleScale = 1.5f)

        val decoded = json.decodeFromString<ViewerConfig>(json.encodeToString(config))

        assertEquals(1.5f, decoded.bubbleScale)
    }

    @Test
    fun maxZoomDefaultsToTwo() {
        //Default value must preserve current Seeneva behavior (library max scale 2.0)
        assertEquals(2.0f, ViewerConfig().maxZoom)
    }

    @Test
    fun oldStoredConfigWithoutMaxZoomDecodesToTwo() {
        //Simulate a viewer config saved before "maximum zoom" setting existed
        val oldConfig = """{"keep_screen_on":true,"brightness":-1.0,"tts":true}"""

        val decoded = json.decodeFromString<ViewerConfig>(oldConfig)

        assertEquals(2.0f, decoded.maxZoom)
    }

    @Test
    fun maxZoomRoundTrip() {
        val config = ViewerConfig(maxZoom = 3.0f)

        val decoded = json.decodeFromString<ViewerConfig>(json.encodeToString(config))

        assertEquals(3.0f, decoded.maxZoom)
    }
}
