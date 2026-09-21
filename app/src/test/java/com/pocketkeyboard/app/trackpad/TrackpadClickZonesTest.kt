package com.pocketkeyboard.app.trackpad

import androidx.compose.ui.unit.Density
import com.pocketkeyboard.app.hid.MouseButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [clickZones] 几何的纯 JVM 单测：底带的位置、左右分割线、点击归属与极端尺寸。
 *
 * 底带是「短竖线把底部一条横向提示带分成左右两个点击区」这一交互的唯一几何来源，
 * 触控板页与竖屏融合布局共用，因此这里逐条钉死规格：
 * - 点击带横贯触控区整个宽度，贴在底部安全区（28dp）之上；
 * - 左右点击区以触控区水平中线为界，点左半 = 左键、点右半 = 右键；
 * - 分割线（[TrackpadDivider] 的默认尺寸）落在点击带正中央。
 */
class TrackpadClickZonesTest {

    /** 1dp = 1px 的密度：断言里的数字可以直接当 dp 读。 */
    private val density = Density(density = 1f)

    /** 常见竖屏：1080 × 2340 px。 */
    private val portraitWidth = 1080f
    private val portraitHeight = 2340f

    private fun zones(width: Float = portraitWidth, height: Float = portraitHeight) =
        clickZones(widthPx = width, heightPx = height, density = density)

    @Test
    fun `点击带横贯整个宽度并贴在底部安全区之上`() {
        val zones = zones()

        // 整条带：左到右顶到边
        assertEquals(0f, zones.band.left, 0.01f)
        assertEquals(portraitWidth, zones.band.right, 0.01f)
        // 高度 = TrackpadMetrics.CLICK_BAND_HEIGHT（36dp），底边距 = CLICK_BAND_BOTTOM_MARGIN（40dp）
        assertEquals(36f, zones.band.height, 0.01f)
        assertEquals(2340f - 40f - 36f, zones.band.top, 0.01f)
        // 底部安全区（28dp 设备名条）与点击带不重叠
        assertTrue(zones.band.bottom <= portraitHeight - 28f + 0.01f)
    }

    @Test
    fun `左右点击区以水平中线为界`() {
        val zones = zones()

        assertEquals(portraitWidth / 2f, zones.left.right, 0.01f)
        assertEquals(portraitWidth / 2f, zones.right.left, 0.01f)
        assertEquals(0f, zones.left.left, 0.01f)
        assertEquals(portraitWidth, zones.right.right, 0.01f)
        // 两个点击区与整条带同高（分割线只在带内居中，不会越出带外）
        assertEquals(zones.band.top, zones.left.top, 0.01f)
        assertEquals(zones.band.top, zones.right.top, 0.01f)
        assertEquals(zones.band.height, zones.left.height, 0.01f)
        assertEquals(zones.band.height, zones.right.height, 0.01f)
    }

    @Test
    fun `点左半是左键点右半是右键`() {
        val zones = zones()

        val midY = zones.band.top + zones.band.height / 2f
        assertEquals(MouseButton.LEFT, zones.zoneOf(10f, midY))
        assertEquals(MouseButton.LEFT, zones.zoneOf(portraitWidth / 2f - 1f, midY))
        assertEquals(MouseButton.RIGHT, zones.zoneOf(portraitWidth / 2f + 1f, midY))
        assertEquals(MouseButton.RIGHT, zones.zoneOf(portraitWidth - 10f, midY))
        // 中线本身归左半（先判左后判右，分割线只有 1.5dp，落在哪半都不影响手感）
        assertEquals(MouseButton.LEFT, zones.zoneOf(portraitWidth / 2f, midY))

        // 带的上下外侧不属于任何点击区
        assertNull(zones.zoneOf(10f, zones.band.top - 1f))
        assertNull(zones.zoneOf(10f, zones.band.bottom + 1f))
        assertFalse(zones.contains(10f, zones.band.top - 1f))
    }

    @Test
    fun `分割线默认尺寸落在点击带内且规格符合改造要求`() {
        // 规格：高 28–36dp、宽 1–2dp、白色 30%–40% 透明度
        val heightPx = with(density) { TrackpadMetrics.CLICK_DIVIDER_HEIGHT.toPx() }
        val widthPx = with(density) { TrackpadMetrics.CLICK_DIVIDER_WIDTH.toPx() }
        assertTrue("分割线高度需在 28–36dp", heightPx in 28f..36f)
        assertTrue("分割线宽度需在 1–2dp", widthPx in 1f..2f)
        assertTrue("分割线透明度需在 30%–40%", TrackpadMetrics.CLICK_DIVIDER_ALPHA in 0.30f..0.40f)

        // 分割线比点击带矮：居中后上下都留在带内
        val zones = zones()
        assertTrue(with(density) { TrackpadMetrics.CLICK_DIVIDER_HEIGHT.toPx() } < zones.band.height)
    }

    @Test
    fun `触控区比点击带还矮时贴顶放置不越界`() {
        val zones = zones(width = 1080f, height = 60f)

        assertTrue(zones.band.top >= 0f)
        assertTrue(zones.band.bottom <= 60f)
        // 依然左右对半分割
        assertEquals(540f, zones.left.right, 0.01f)
        assertEquals(540f, zones.right.left, 0.01f)
        assertEquals(MouseButton.LEFT, zones.zoneOf(100f, 30f))
        assertEquals(MouseButton.RIGHT, zones.zoneOf(1000f, 30f))
    }
}
