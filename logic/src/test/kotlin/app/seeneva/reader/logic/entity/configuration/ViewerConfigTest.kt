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
}
