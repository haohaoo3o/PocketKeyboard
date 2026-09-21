package com.pocketkeyboard.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 边缘内滑激活 dock 的几何判定纯函数单测：
 * 落在左右 24dp 激活带内才接管、向内滑过触发距离才激活、向外滑永不触发。
 */
class EdgeSwipeGeometryTest {

    private val zone = 24f
    private val width = 1080f

    @Test
    fun `down near left or right edge is recognized`() {
        assertEquals(EdgeSwipeGeometry.Side.LEFT, EdgeSwipeGeometry.sideOf(0f, width, zone))
        assertEquals(EdgeSwipeGeometry.Side.LEFT, EdgeSwipeGeometry.sideOf(24f, width, zone))
        assertEquals(
            EdgeSwipeGeometry.Side.RIGHT,
            EdgeSwipeGeometry.sideOf(width - 24f, width, zone),
        )
        assertEquals(EdgeSwipeGeometry.Side.RIGHT, EdgeSwipeGeometry.sideOf(width, width, zone))
    }

    @Test
    fun `down in the middle is not an edge swipe`() {
        assertEquals(
            EdgeSwipeGeometry.Side.NONE,
            EdgeSwipeGeometry.sideOf(width / 2f, width, zone),
        )
        assertEquals(
            EdgeSwipeGeometry.Side.NONE,
            EdgeSwipeGeometry.sideOf(zone + 1f, width, zone),
        )
        assertEquals(
            EdgeSwipeGeometry.Side.NONE,
            EdgeSwipeGeometry.sideOf(width - zone - 1f, width, zone),
        )
    }

    @Test
    fun `inward progress grows toward the screen center`() {
        // 左缘：向右滑 = 向内
        assertEquals(0f, EdgeSwipeGeometry.inwardProgress(EdgeSwipeGeometry.Side.LEFT, 0f, 0f), 0.001f)
        assertEquals(30f, EdgeSwipeGeometry.inwardProgress(EdgeSwipeGeometry.Side.LEFT, 0f, 30f), 0.001f)
        assertEquals(-10f, EdgeSwipeGeometry.inwardProgress(EdgeSwipeGeometry.Side.LEFT, 20f, 10f), 0.001f)
        // 右缘：向左滑 = 向内
        assertEquals(
            30f,
            EdgeSwipeGeometry.inwardProgress(EdgeSwipeGeometry.Side.RIGHT, width, width - 30f),
            0.001f,
        )
        assertEquals(
            -10f,
            EdgeSwipeGeometry.inwardProgress(EdgeSwipeGeometry.Side.RIGHT, width - 20f, width - 10f),
            0.001f,
        )
        // 非边缘：永远 0（不会被误判为滑动）
        assertEquals(
            0f,
            EdgeSwipeGeometry.inwardProgress(EdgeSwipeGeometry.Side.NONE, 100f, 500f),
            0.001f,
        )
    }

    @Test
    fun `trigger threshold needs more than 24dp of inward travel`() {
        // 24dp 触发距离：位移 23.9 不触发、24 触发（判定在 MainActivity 的手势循环里做）
        val trigger = 24f
        assert(EdgeSwipeGeometry.inwardProgress(EdgeSwipeGeometry.Side.LEFT, 0f, 23.9f) < trigger)
        assert(EdgeSwipeGeometry.inwardProgress(EdgeSwipeGeometry.Side.LEFT, 0f, 24f) >= trigger)
    }
}
