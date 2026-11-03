package com.cactuslauncher.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WinterSceneTest {
    @Test
    fun landscapeStaysOnGridAndInsideResizedCanvas() {
        for ((width, height) in listOf(1 to 1, 45 to 60, 180 to 140, 320 to 200, 640 to 360)) {
            val blocks = winterLandscape(width, height)
            assertTrue(blocks.isNotEmpty())
            assertEquals(blocks, winterLandscape(width, height))
            blocks.forEach {
                assertTrue(it.x >= 0 && it.y >= 0)
                assertTrue(it.width > 0 && it.height > 0)
                assertTrue(it.x + it.width <= width && it.y + it.height <= height)
                assertTrue(it.ink in 0..6)
            }
        }
    }

    @Test
    fun fullWindowSceneHasRaisedLandscapeAcrossBothPanes() {
        for ((width, height) in listOf(300 to 184, 320 to 200, 240 to 360, 640 to 160)) {
            val blocks = winterLandscape(width, height)
            assertEquals((0..6).toSet(), blocks.map { it.ink }.toSet())
            assertTrue(blocks.minOf { it.y } in height * 40 / 100..height * 50 / 100)
            assertTrue(blocks.any { it.x == 0 && it.y < height * 60 / 100 })
            assertTrue(blocks.any { it.x + it.width == width && it.y < height * 60 / 100 })
            assertTrue(blocks.any { it.ink == 6 && it.y + it.height == height })
            assertTrue(blocks.size < 1200)
        }
    }

    @Test
    fun snowIsDeterministicBoundedAndLoopsExactly() {
        for ((width, height) in listOf(1 to 1, 45 to 60, 320 to 200, 640 to 360)) {
            val flakes = winterFlakes(width, height)
            assertEquals(flakes, winterFlakes(width, height))
            assertTrue(flakes.size in 12..90)
            flakes.forEach { flake ->
                for (tick in 0 until WINTER_LOOP_TICKS) {
                    val x = winterSnowX(flake, tick, width)
                    val y = winterSnowY(flake, tick, height)
                    assertTrue(x in 0 until width && y in 0 until height)
                    assertEquals(x, winterSnowX(flake, tick + WINTER_LOOP_TICKS, width))
                    assertEquals(y, winterSnowY(flake, tick + WINTER_LOOP_TICKS, height))
                }
            }
        }
    }

    @Test
    fun snowCoversSidebarHeaderAndFullWindowHeight() {
        for ((width, height) in listOf(300 to 184, 320 to 200, 240 to 360, 640 to 160)) {
            val flakes = winterFlakes(width, height)
            val positions = (0 until WINTER_LOOP_TICKS step 100).flatMap { tick ->
                flakes.map { winterSnowX(it, tick, width) to winterSnowY(it, tick, height) }
            }
            assertTrue(positions.any { (x, y) -> x < width / 5 && y < height / 4 })
            assertTrue(positions.any { (x, y) -> x >= width / 5 && y < height / 4 })
            assertTrue(positions.any { (_, y) -> y >= height * 3 / 4 })
        }
    }

    @Test
    fun snowMovesInTwoDepthLayersWithoutReplacingLandscape() {
        val flakes = winterFlakes(320, 200)
        assertTrue(flakes.any { it.near } && flakes.any { !it.near })
        val far = flakes.first { !it.near }
        val near = flakes.first { it.near }
        assertEquals(25, (winterSnowY(far, 150, 200) - far.y).mod(200))
        assertEquals(50, (winterSnowY(near, 150, 200) - near.y).mod(200))
    }

    @Test
    fun seasonalChoiceRestoresSnapshotsAndResetUsesAutomaticSeason() {
        val settings = LauncherSettings("/games")
        settings.seasonalTheme = "winter"
        settings.theme = "dark"
        val saved = settings.snapshot(setupComplete = true)
        val restored = LauncherSettings("/other")
        restored.restore(saved)
        assertEquals("winter", restored.seasonalTheme)
        assertEquals("dark", restored.theme)
        assertEquals(saved, restored.snapshot(setupComplete = true))
        restored.reset("/games")
        assertEquals("auto", restored.seasonalTheme)
        assertEquals("system", restored.theme)
    }
}
