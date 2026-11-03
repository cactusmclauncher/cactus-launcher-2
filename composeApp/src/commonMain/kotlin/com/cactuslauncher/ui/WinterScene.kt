package com.cactuslauncher.ui

import kotlin.math.max

internal data class WinterBlock(val x: Int, val y: Int, val width: Int, val height: Int, val ink: Int)
internal data class WinterFlake(val x: Int, val y: Int, val near: Boolean)

internal const val WINTER_LOOP_TICKS = 1200

internal fun winterLandscape(width: Int, height: Int): List<WinterBlock> {
    require(width > 0 && height > 0)
    val blocks = ArrayList<WinterBlock>()
    fun block(x: Int, y: Int, w: Int, h: Int, ink: Int) {
        val left = x.coerceIn(0, width)
        val top = y.coerceIn(0, height)
        val right = (x + w).coerceIn(0, width)
        val bottom = (y + h).coerceIn(0, height)
        if (right > left && bottom > top) blocks.add(WinterBlock(left, top, right - left, bottom - top, ink))
    }
    val horizon = height * 56 / 100
    val relief = max(3, height / 12)
    val ridge = intArrayOf(1, 2, 4, 6, 8, 6, 5, 3, 2, 4, 5, 7, 9, 7, 4, 3, 1, 3, 5, 6, 4, 2)
    for (i in ridge.indices) {
        val left = i * width / ridge.size
        val right = (i + 1) * width / ridge.size
        val top = horizon - ridge[i] * relief / 9
        block(left, top, right - left, height - top, 0)
        block(left, top, right - left, 2, 1)
        if (ridge[i] >= 6) block(left + 1, top + 3, max(1, right - left - 2), 1, 1)
    }
    fun spruce(x: Int, ground: Int, size: Int, distant: Boolean) {
        val ink = if (distant) 2 else 5
        block(x, ground - size, 1, size, ink)
        val tierHeight = max(1, (size - 2) / 3)
        for (tier in 0..2) {
            val half = 1 + tier * (if (distant) 1 else 2)
            val y = ground - size + 1 + tier * tierHeight
            block(x - half, y, half * 2 + 1, tierHeight, ink)
            if (!distant) block(x - half, y, half + 1, 1, 4)
        }
    }
    val forestFloor = height * 70 / 100
    for (i in 0 until width / 9) {
        if (i % 7 != 3 && i % 7 != 4) spruce(i * 9 + 3, forestFloor, 6 + i % 3, true)
    }
    val bank = intArrayOf(1, 1, 0, 0, 1, 2, 2, 3, 3, 2, 1, 1, 0, 1, 2, 2)
    for (i in bank.indices) {
        val x = i * width / bank.size
        val w = (i + 1) * width / bank.size - x
        val top = forestFloor + bank[i]
        block(x, top, w, height - top, 3)
        block(x, top, w, 1, 4)
    }
    for (y in forestFloor + 4 until height step 2) {
        val depth = y - forestFloor
        val center = width * 70 / 100 + depth / 3
        val half = 2 + depth / 4
        block(center - half, y, half * 2, 2, 6)
        block(center - half, y, 2, 1, 4)
        if (depth % 8 == 0) block(center, y, max(2, half / 2), 1, 1)
    }
    if (width >= 100 && height >= 80) {
        val x = width * 88 / 100
        val y = forestFloor + 5
        block(x - 7, y - 7, 14, 7, 5)
        block(x - 8, y - 8, 16, 2, 4)
        block(x - 6, y - 10, 12, 2, 4)
        block(x - 4, y - 12, 8, 2, 4)
        block(x - 4, y - 5, 3, 2, 6)
        block(x + 3, y - 4, 2, 4, 2)
        block(x - 7, y, 15, 1, 1)
    }
    if (height >= 80) {
        spruce(width - 9, height - 5, 17, false)
        spruce(width - 20, height - 2, 12, false)
        spruce(6, height - 3, 12, false)
    }
    for (i in 0 until width / 22) {
        block(i * 22 + 11, height - 3 - i % 4, 4, 1, 1)
    }
    return blocks
}

internal fun winterFlakes(width: Int, height: Int): List<WinterFlake> {
    require(width > 0 && height > 0)
    val count = (width * height / 750).coerceIn(12, 90)
    return List(count) { i ->
        WinterFlake((i * 73 + 19) % width, (i * 47 + 11) % height, i % 3 == 0)
    }
}

internal fun winterSnowX(flake: WinterFlake, tick: Int, width: Int): Int {
    val phase = tick.mod(WINTER_LOOP_TICKS)
    val sway = if (phase < WINTER_LOOP_TICKS / 2) phase else WINTER_LOOP_TICKS - phase
    return (flake.x + sway / (if (flake.near) 100 else 150)).mod(width)
}

internal fun winterSnowY(flake: WinterFlake, tick: Int, height: Int): Int {
    val phase = tick.mod(WINTER_LOOP_TICKS)
    return (flake.y + phase * height * (if (flake.near) 2 else 1) / WINTER_LOOP_TICKS).mod(height)
}
