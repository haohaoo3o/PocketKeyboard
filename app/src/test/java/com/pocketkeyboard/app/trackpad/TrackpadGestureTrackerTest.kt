package com.pocketkeyboard.app.trackpad

import com.pocketkeyboard.app.hid.MouseButton
import com.pocketkeyboard.app.ui.DevicePlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TrackpadGestureTracker] 的纯 JVM 单测：逐条覆盖 Win / Apple 两套手势清单。
 *
 * 这里不碰 Compose：手势引擎只吃 [FingerSample]（手指数、质心、间距、旋转角、
 * 按键区按住状态），因此可以在 JVM 上完整驱动。
 */
class TrackpadGestureTrackerTest {

    /** 与 UI 层一致的阈值：触摸 slop 12px、三指挥手 120px。 */
    private val thresholds = TrackpadThresholds(tapSlopPx = 12f, swipeDistancePx = 120f)

    private fun tracker(platform: DevicePlatform) =
        TrackpadGestureTracker(platform, thresholds)

    /** 构造一个手指快照。 */
    private fun sample(
        fingers: Int,
        x: Float = 0f,
        y: Float = 0f,
        spread: Float = 0f,
        rotation: Float = 0f,
        button: MouseButton? = null,
        uptime: Long = 0L,
    ) = FingerSample(
        trackpadFingers = fingers,
        buttonHeld = button,
        centroidX = x,
        centroidY = y,
        spread = spread,
        rotationDeg = rotation,
        uptimeMillis = uptime,
    )

    // ---------------------------------------------------------------- 1 指

    @Test
    fun `单指拖动产出指针位移并按灵敏度放大`() {
        val tracker = tracker(DevicePlatform.OTHER)

        assertNull(tracker.onSample(sample(fingers = 1, x = 0f, y = 0f, uptime = 0L)))
        assertEquals(TrackpadGesture.Move(15, 0), tracker.onSample(sample(fingers = 1, x = 10f, y = 0f, uptime = 10L)))
        assertEquals(TrackpadGesture.Move(0, 30), tracker.onSample(sample(fingers = 1, x = 10f, y = 20f, uptime = 20L)))
        // 大幅快滑被裁剪到单次上限，避免 HID 单字节轴溢出
        assertEquals(TrackpadGesture.Move(-120, 0), tracker.onSample(sample(fingers = 1, x = -100f, y = 20f, uptime = 30L)))
        // 拖动过的手势不再判点击
        assertNull(tracker.finish())
    }

    @Test
    fun `单指轻点是左键`() {
        val tracker = tracker(DevicePlatform.OTHER)

        assertNull(tracker.onSample(sample(fingers = 1, x = 0f, y = 0f, uptime = 0L)))
        // 手指抖动（位移小于触摸 slop）仍会产生一两个像素的指针位移，但不影响点击判定
        assertEquals(
            TrackpadGesture.Move(2, 2),
            tracker.onSample(sample(fingers = 1, x = 1f, y = 1f, uptime = 120L)),
        )

        assertEquals(TrackpadGesture.Click(MouseButton.LEFT), tracker.finish())
    }

    @Test
    fun `按下即抬起的轻点也判为左键`() {
        // 真机上一次轻点可能没有任何中间事件（DOWN 之后直接 UP），
        // 只有第一个快照 —— 此时也必须能判出点击
        val tracker = tracker(DevicePlatform.OTHER)

        assertNull(tracker.onSample(sample(fingers = 1, x = 0f, y = 0f, uptime = 0L)))

        assertEquals(TrackpadGesture.Click(MouseButton.LEFT), tracker.finish())
    }

    @Test
    fun `双指摆位时质心跳变不计入点击位移`() {
        val tracker = tracker(DevicePlatform.OTHER)

        // 第一根手指落下
        tracker.onSample(sample(fingers = 1, x = 0f, y = 0f, spread = 0f, uptime = 0L))
        // 第二根手指在 40px 外落下：质心从「第一根的位置」跳到中点（20px），这是摆位不是移动
        tracker.onSample(sample(fingers = 2, x = 20f, y = 0f, spread = 40f, uptime = 50L))
        // 之后两根手指一起保持不动
        tracker.onSample(sample(fingers = 2, x = 20f, y = 0f, spread = 40f, uptime = 100L))

        assertEquals(TrackpadGesture.Click(MouseButton.RIGHT), tracker.finish())
    }

    @Test
    fun `拖动过后即使手指数变化也不判点击`() {
        val tracker = tracker(DevicePlatform.OTHER)

        tracker.onSample(sample(fingers = 1, x = 0f, y = 0f, uptime = 0L))
        // 单指拖了 100px
        tracker.onSample(sample(fingers = 1, x = 0f, y = 100f, uptime = 16L))
        // 补上第二根手指（换阶段、里程重新累计）
        tracker.onSample(sample(fingers = 2, x = 0f, y = 100f, spread = 30f, uptime = 32L))
        // 抬起第二根，之后不动
        tracker.onSample(sample(fingers = 1, x = 0f, y = 100f, uptime = 48L))

        assertNull("拖拽过的手势不能再判成点击", tracker.finish())
    }

    @Test
    fun `多个阶段的小位移累计超过 slop 也不判点击`() {
        val tracker = tracker(DevicePlatform.OTHER)

        // 第一阶段（单指）走 10px：单独看没超过 slop 12px
        tracker.onSample(sample(fingers = 1, x = 0f, y = 0f, uptime = 0L))
        tracker.onSample(sample(fingers = 1, x = 0f, y = 10f, uptime = 16L))
        // 第二根手指落下（换阶段，质心跳变不计）
        tracker.onSample(sample(fingers = 2, x = 0f, y = 10f, spread = 30f, uptime = 32L))
        // 第二阶段（双指）再走 10px：两段累计 20px > slop，整体是拖动不是点击
        tracker.onSample(sample(fingers = 2, x = 0f, y = 20f, spread = 30f, uptime = 48L))

        assertNull("分阶段的小位移累计超过 slop 后不能再判成点击", tracker.finish())
    }

    @Test
    fun `单指长按不产出点击`() {
        val tracker = tracker(DevicePlatform.OTHER)

        tracker.onSample(sample(fingers = 1, x = 0f, y = 0f, uptime = 0L))
        tracker.onSample(sample(fingers = 1, x = 0f, y = 0f, uptime = 600L))

        assertNull(tracker.finish())
    }

    @Test
    fun `按住左键区加滑动产出指针位移`() {
        val tracker = tracker(DevicePlatform.OTHER)

        assertNull(tracker.onSample(sample(fingers = 1, x = 0f, y = 0f, button = MouseButton.LEFT, uptime = 0L)))
        assertEquals(
            TrackpadGesture.Move(30, 0),
            tracker.onSample(sample(fingers = 1, x = 20f, y = 0f, button = MouseButton.LEFT, uptime = 16L)),
        )
        // 右键区同理
        assertEquals(
            TrackpadGesture.Move(0, 45),
            tracker.onSample(sample(fingers = 1, x = 20f, y = 30f, button = MouseButton.RIGHT, uptime = 32L)),
        )
    }

    @Test
    fun `只有按键区的手指时触控板层不产出手势`() {
        val tracker = tracker(DevicePlatform.OTHER)

        assertNull(tracker.onSample(sample(fingers = 0, button = MouseButton.LEFT, uptime = 0L)))
        assertNull(tracker.onSample(sample(fingers = 0, button = MouseButton.LEFT, uptime = 16L)))
        // 按键的按下 / 松开由按键区自己负责，这里连点击都不该补
        assertNull(tracker.finish())
    }

    // ---------------------------------------------------------------- 2 指

    @Test
    fun `双指拖动产出滚轮`() {
        val tracker = tracker(DevicePlatform.OTHER)

        assertNull(tracker.onSample(sample(fingers = 2, x = 0f, y = 0f, spread = 100f, uptime = 0L)))
        // 垂直滚动：24px / 12px 每格 = 2 格
        assertEquals(TrackpadGesture.Scroll(0, 2), tracker.onSample(sample(fingers = 2, x = 0f, y = 24f, spread = 100f, uptime = 16L)))
        // 水平滚动：走 AC Pan（sendScroll 的 dx）
        assertEquals(TrackpadGesture.Scroll(3, 0), tracker.onSample(sample(fingers = 2, x = 36f, y = 24f, spread = 100f, uptime = 32L)))
        // 单次滚动格数有上限
        assertEquals(TrackpadGesture.Scroll(0, 10), tracker.onSample(sample(fingers = 2, x = 36f, y = 200f, spread = 100f, uptime = 48L)))
    }

    @Test
    fun `双指轻点是右键`() {
        val tracker = tracker(DevicePlatform.OTHER)

        tracker.onSample(sample(fingers = 2, x = 0f, y = 0f, spread = 100f, uptime = 0L))
        // 位移与滚动量都小于一个阈值 → 视为双指点击
        assertNull(tracker.onSample(sample(fingers = 2, x = 2f, y = 2f, spread = 100f, uptime = 100L)))

        assertEquals(TrackpadGesture.Click(MouseButton.RIGHT), tracker.finish())
    }

    @Test
    fun `双指捏合产出缩放档位`() {
        val tracker = tracker(DevicePlatform.OTHER)

        tracker.onSample(sample(fingers = 2, x = 0f, y = 0f, spread = 100f, uptime = 0L))
        // 间距 100 → 116（+16%）：跨过 15% 档位 → 放大 1 格
        assertEquals(TrackpadGesture.Zoom(1), tracker.onSample(sample(fingers = 2, x = 0f, y = 0f, spread = 116f, uptime = 16L)))
        // 回到 100 → 缩回 1 格
        assertEquals(TrackpadGesture.Zoom(-1), tracker.onSample(sample(fingers = 2, x = 0f, y = 0f, spread = 100f, uptime = 32L)))
        // 缩到 85（-15% 档位）→ 再缩小 1 格
        assertEquals(TrackpadGesture.Zoom(-1), tracker.onSample(sample(fingers = 2, x = 0f, y = 0f, spread = 85f, uptime = 48L)))
        // 捏合过的手势不再判点击
        assertNull(tracker.finish())
    }

    @Test
    fun `双指捏合时不再滚动`() {
        val tracker = tracker(DevicePlatform.OTHER)

        tracker.onSample(sample(fingers = 2, x = 0f, y = 0f, spread = 100f, uptime = 0L))
        // 先捏合一档（进入「捏合中」）
        assertEquals(TrackpadGesture.Zoom(1), tracker.onSample(sample(fingers = 2, x = 0f, y = 0f, spread = 120f, uptime = 16L)))
        // 之后即使质心继续下移也不再滚轮，避免一边放大一边滚动
        assertNull(tracker.onSample(sample(fingers = 2, x = 0f, y = 60f, spread = 120f, uptime = 32L)))
    }

    @Test
    fun `双指旋转也映射为缩放档位`() {
        val tracker = tracker(DevicePlatform.APPLE)

        tracker.onSample(sample(fingers = 2, x = 0f, y = 0f, spread = 100f, rotation = 0f, uptime = 0L))
        // 10°：超过 8° 死区进入「旋转中」，但还没到 15° 档位 → 本拍无输出
        assertNull(tracker.onSample(sample(fingers = 2, x = 0f, y = 0f, spread = 100f, rotation = 10f, uptime = 16L)))
        // 累计 30° → 2 档
        assertEquals(TrackpadGesture.Zoom(2), tracker.onSample(sample(fingers = 2, x = 0f, y = 0f, spread = 100f, rotation = 30f, uptime = 32L)))
        // 转回 0° → 撤掉 2 档
        assertEquals(TrackpadGesture.Zoom(-2), tracker.onSample(sample(fingers = 2, x = 0f, y = 0f, spread = 100f, rotation = 0f, uptime = 48L)))
    }

    @Test
    fun `手指数变化时重新取基准避免巨大跳变`() {
        val tracker = tracker(DevicePlatform.OTHER)

        tracker.onSample(sample(fingers = 2, x = 0f, y = 0f, spread = 100f, uptime = 0L))
        assertEquals(TrackpadGesture.Scroll(0, 3), tracker.onSample(sample(fingers = 2, x = 0f, y = 30f, spread = 100f, uptime = 16L)))
        // 抬起一根手指：这一拍只换基准，不产出位移
        assertNull(tracker.onSample(sample(fingers = 1, x = 0f, y = 30f, uptime = 32L)))
        // 之后按新基准算位移
        assertEquals(TrackpadGesture.Move(0, 15), tracker.onSample(sample(fingers = 1, x = 0f, y = 40f, uptime = 48L)))
    }

    // ---------------------------------------------------------------- 3 指

    @Test
    fun `windows 三指上滑是任务视图`() {
        val tracker = tracker(DevicePlatform.OTHER)

        tracker.onSample(sample(fingers = 3, x = 0f, y = 0f, spread = 100f, uptime = 0L))
        assertEquals(
            TrackpadGesture.TaskView,
            tracker.onSample(sample(fingers = 3, x = 0f, y = -130f, spread = 100f, uptime = 16L)),
        )
        // 一次挥手只触发一次
        assertNull(tracker.onSample(sample(fingers = 3, x = 0f, y = -200f, spread = 100f, uptime = 32L)))
        assertNull(tracker.finish())
    }

    @Test
    fun `windows 三指下滑是显示桌面`() {
        val tracker = tracker(DevicePlatform.OTHER)

        tracker.onSample(sample(fingers = 3, x = 0f, y = 0f, spread = 100f, uptime = 0L))
        assertEquals(
            TrackpadGesture.ShowDesktop,
            tracker.onSample(sample(fingers = 3, x = 0f, y = 130f, spread = 100f, uptime = 16L)),
        )
    }

    @Test
    fun `windows 三指左右滑是切换应用`() {
        val tracker = tracker(DevicePlatform.OTHER)

        tracker.onSample(sample(fingers = 3, x = 0f, y = 0f, spread = 100f, uptime = 0L))
        assertEquals(
            TrackpadGesture.SwitchApp(forward = true),
            tracker.onSample(sample(fingers = 3, x = 130f, y = 100f, spread = 100f, uptime = 16L)),
        )

        val backward = tracker(DevicePlatform.OTHER)
        backward.onSample(sample(fingers = 3, x = 0f, y = 0f, spread = 100f, uptime = 0L))
        assertEquals(
            TrackpadGesture.SwitchApp(forward = false),
            backward.onSample(sample(fingers = 3, x = -130f, y = 0f, spread = 100f, uptime = 16L)),
        )
    }

    @Test
    fun `windows 三指位移不足阈值不触发`() {
        val tracker = tracker(DevicePlatform.OTHER)

        tracker.onSample(sample(fingers = 3, x = 0f, y = 0f, spread = 100f, uptime = 0L))
        assertNull(tracker.onSample(sample(fingers = 3, x = 0f, y = -60f, spread = 100f, uptime = 16L)))
        assertNull(tracker.finish())
    }

    @Test
    fun `apple 三指上滑是 mission control`() {
        val tracker = tracker(DevicePlatform.APPLE)

        tracker.onSample(sample(fingers = 3, x = 0f, y = 0f, spread = 100f, uptime = 0L))
        assertEquals(
            TrackpadGesture.MissionControl,
            tracker.onSample(sample(fingers = 3, x = 0f, y = -130f, spread = 100f, uptime = 16L)),
        )
    }

    @Test
    fun `apple 三指下滑没有映射`() {
        val tracker = tracker(DevicePlatform.APPLE)

        tracker.onSample(sample(fingers = 3, x = 0f, y = 0f, spread = 100f, uptime = 0L))
        // App Exposé 没有等价 HID 用法，故意不映射
        assertNull(tracker.onSample(sample(fingers = 3, x = 0f, y = 130f, spread = 100f, uptime = 16L)))
        assertNull(tracker.finish())
    }

    @Test
    fun `apple 三指左右滑是切换全屏空间`() {
        val tracker = tracker(DevicePlatform.APPLE)

        tracker.onSample(sample(fingers = 3, x = 0f, y = 0f, spread = 100f, uptime = 0L))
        assertEquals(
            TrackpadGesture.SwitchSpace(forward = true),
            tracker.onSample(sample(fingers = 3, x = 130f, y = 0f, spread = 100f, uptime = 16L)),
        )

        val backward = tracker(DevicePlatform.APPLE)
        backward.onSample(sample(fingers = 3, x = 0f, y = 0f, spread = 100f, uptime = 0L))
        assertEquals(
            TrackpadGesture.SwitchSpace(forward = false),
            backward.onSample(sample(fingers = 3, x = -130f, y = 0f, spread = 100f, uptime = 16L)),
        )
    }

    // ---------------------------------------------------------------- 4 指

    @Test
    fun `windows 四指轻点是 win 键`() {
        val tracker = tracker(DevicePlatform.OTHER)

        tracker.onSample(sample(fingers = 4, x = 0f, y = 0f, spread = 100f, uptime = 0L))
        tracker.onSample(sample(fingers = 4, x = 1f, y = 1f, spread = 100f, uptime = 120L))

        assertEquals(TrackpadGesture.WinKey, tracker.finish())
    }

    @Test
    fun `apple 四指轻点是 launchpad 预留语义`() {
        val tracker = tracker(DevicePlatform.APPLE)

        tracker.onSample(sample(fingers = 4, x = 0f, y = 0f, spread = 100f, uptime = 0L))
        tracker.onSample(sample(fingers = 4, x = 1f, y = 1f, spread = 100f, uptime = 120L))

        // 语义上识别出来了，但 TrackpadHidActions 不会真的发送
        assertEquals(TrackpadGesture.Launchpad, tracker.finish())
    }

    @Test
    fun `apple 四指捏合不算点击`() {
        val tracker = tracker(DevicePlatform.APPLE)

        tracker.onSample(sample(fingers = 4, x = 0f, y = 0f, spread = 100f, uptime = 0L))
        tracker.onSample(sample(fingers = 4, x = 0f, y = 0f, spread = 130f, uptime = 16L))

        assertNull(tracker.finish())
    }

    // ---------------------------------------------------------------- 几何工具

    @Test
    fun `角度差归一化到正负 180 度`() {
        assertEquals(20f, TrackpadGestureTracker.normalizeAngle(20f), 0.001f)
        assertEquals(-20f, TrackpadGestureTracker.normalizeAngle(-20f), 0.001f)
        assertEquals(20f, TrackpadGestureTracker.normalizeAngle(380f), 0.001f)
        assertEquals(-170f, TrackpadGestureTracker.normalizeAngle(190f), 0.001f)
        // 跨过 ±180° 时只该有 20° 差，而不是 -340°
        assertEquals(20f, TrackpadGestureTracker.normalizeAngle(-340f), 0.001f)
    }

    @Test
    fun `双指连线角度`() {
        assertEquals(0f, TrackpadGestureTracker.angleBetween(0f, 0f, 10f, 0f), 0.001f)
        assertEquals(90f, TrackpadGestureTracker.angleBetween(0f, 0f, 0f, 10f), 0.001f)
        assertEquals(-90f, TrackpadGestureTracker.angleBetween(0f, 0f, 0f, -10f), 0.001f)
    }

    @Test
    fun `累计量按档位向零外侧取整`() {
        assertEquals(0, TrackpadGestureTracker.notchesFor(14f, 15f))
        assertEquals(1, TrackpadGestureTracker.notchesFor(15f, 15f))
        assertEquals(2, TrackpadGestureTracker.notchesFor(31f, 15f))
        assertEquals(0, TrackpadGestureTracker.notchesFor(-14f, 15f))
        assertEquals(-1, TrackpadGestureTracker.notchesFor(-15f, 15f))
        assertEquals(-2, TrackpadGestureTracker.notchesFor(-31f, 15f))
    }

    @Test
    fun `间距比例按对数刻度换算缩放档位`() {
        assertEquals(0, TrackpadGestureTracker.zoomNotchFor(1f))
        assertEquals(0, TrackpadGestureTracker.zoomNotchFor(1.05f))
        assertEquals(1, TrackpadGestureTracker.zoomNotchFor(1.15f))
        assertEquals(2, TrackpadGestureTracker.zoomNotchFor(1.15f * 1.15f))
        assertEquals(-1, TrackpadGestureTracker.zoomNotchFor(1f / 1.15f))
        assertEquals(-2, TrackpadGestureTracker.zoomNotchFor(1f / (1.15f * 1.15f)))
    }

    @Test
    fun `三指横滑在两个平台映射到不同手势`() {
        // 手势层只认 DevicePlatform.APPLE / OTHER；「没有控制设备」时的兜底平台由 UI 层决定
        val other = tracker(DevicePlatform.OTHER)
        val apple = tracker(DevicePlatform.APPLE)
        other.onSample(sample(fingers = 3, x = 0f, y = 0f, spread = 100f, uptime = 0L))
        apple.onSample(sample(fingers = 3, x = 0f, y = 0f, spread = 100f, uptime = 0L))
        assertTrue(
            other.onSample(sample(fingers = 3, x = 130f, y = 0f, spread = 100f, uptime = 16L))
                is TrackpadGesture.SwitchApp,
        )
        assertTrue(
            apple.onSample(sample(fingers = 3, x = 130f, y = 0f, spread = 100f, uptime = 16L))
                is TrackpadGesture.SwitchSpace,
        )
    }
}
