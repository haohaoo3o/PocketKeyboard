package com.pocketkeyboard.app.trackpad

import com.pocketkeyboard.app.hid.HidTransport
import com.pocketkeyboard.app.hid.MouseButton
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TrackpadHidActions] 的发送序单测：用一个按顺序记录事件的假 transport，
 * 断言「组合手势必须先按修饰键、发完立刻松开」这条契约真的被遵守。
 */
class TrackpadHidActionsTest {

    /** 假传输：把所有调用按顺序记成可读字符串。 */
    private class RecordingTransport : HidTransport {
        val events = mutableListOf<String>()

        override fun start() {
            events += "start"
        }

        override fun stop() {
            events += "stop"
        }

        override fun sendKeyboardReport(modifiers: Int, keyCodes: ByteArray) {
            val keys = keyCodes.joinToString(",") { "0x${(it.toInt() and 0xFF).toString(16)}" }
            events += "key(mod=0x${modifiers.toString(16)}, keys=[$keys])"
        }

        override fun sendConsumerUsage(usage: Int) {
            events += "consumer(0x${usage.toString(16)})"
        }

        override fun sendMouseMove(dx: Int, dy: Int) {
            events += "move($dx, $dy)"
        }

        override fun sendScroll(dx: Int, dy: Int) {
            events += "scroll($dx, $dy)"
        }

        override fun sendMouseButton(button: MouseButton, pressed: Boolean) {
            events += "button($button, $pressed)"
        }
    }

    private val transport = RecordingTransport()
    private val actions = TrackpadHidActions(transport)

    @Test
    fun `指针位移直接走 sendMouseMove`() {
        actions.dispatch(TrackpadGesture.Move(12, -7))

        assertEquals(listOf("move(12, -7)"), transport.events)
    }

    @Test
    fun `滚轮直接走 sendScroll`() {
        actions.dispatch(TrackpadGesture.Scroll(3, 2))

        assertEquals(listOf("scroll(3, 2)"), transport.events)
    }

    @Test
    fun `点击是按下后立刻松开`() {
        actions.dispatch(TrackpadGesture.Click(MouseButton.LEFT))

        assertEquals(listOf("button(LEFT, true)", "button(LEFT, false)"), transport.events)
    }

    @Test
    fun `捏合缩放先按 ctrl 再滚轮 发完松开`() {
        actions.dispatch(TrackpadGesture.Zoom(2))

        assertEquals(
            listOf(
                "key(mod=0x1, keys=[])", // LeftCtrl 按下
                "scroll(0, -1)", // 放大 = 滚轮向上
                "scroll(0, -1)",
                "key(mod=0x0, keys=[])", // 修饰键松开
            ),
            transport.events,
        )
    }

    @Test
    fun `捏合缩小的滚轮方向相反`() {
        actions.dispatch(TrackpadGesture.Zoom(-1))

        assertEquals(
            listOf(
                "key(mod=0x1, keys=[])",
                "scroll(0, 1)",
                "key(mod=0x0, keys=[])",
            ),
            transport.events,
        )
    }

    @Test
    fun `四指点击发送 win 修饰键后立刻松开`() {
        actions.dispatch(TrackpadGesture.WinKey)

        assertEquals(
            listOf("key(mod=0x8, keys=[])", "key(mod=0x0, keys=[])"),
            transport.events,
        )
    }

    @Test
    fun `windows 任务视图映射 win 加 tab`() {
        actions.dispatch(TrackpadGesture.TaskView)

        assertEquals(
            listOf("key(mod=0x8, keys=[0x2b])", "key(mod=0x0, keys=[])"),
            transport.events,
        )
    }

    @Test
    fun `windows 显示桌面映射 win 加 d`() {
        actions.dispatch(TrackpadGesture.ShowDesktop)

        assertEquals(
            listOf("key(mod=0x8, keys=[0x7])", "key(mod=0x0, keys=[])"),
            transport.events,
        )
    }

    @Test
    fun `windows 切换应用映射 alt 加 tab 与 alt shift tab`() {
        actions.dispatch(TrackpadGesture.SwitchApp(forward = true))
        assertEquals(
            listOf("key(mod=0x4, keys=[0x2b])", "key(mod=0x0, keys=[])"),
            transport.events,
        )

        transport.events.clear()
        actions.dispatch(TrackpadGesture.SwitchApp(forward = false))
        assertEquals(
            listOf("key(mod=0x6, keys=[0x2b])", "key(mod=0x0, keys=[])"),
            transport.events,
        )
    }

    @Test
    fun `apple mission control 映射 ctrl 加上方向键`() {
        actions.dispatch(TrackpadGesture.MissionControl)

        assertEquals(
            listOf("key(mod=0x1, keys=[0x52])", "key(mod=0x0, keys=[])"),
            transport.events,
        )
    }

    @Test
    fun `apple 切换空间映射 ctrl 加左右方向键`() {
        actions.dispatch(TrackpadGesture.SwitchSpace(forward = true))
        assertEquals(
            listOf("key(mod=0x1, keys=[0x4f])", "key(mod=0x0, keys=[])"),
            transport.events,
        )

        transport.events.clear()
        actions.dispatch(TrackpadGesture.SwitchSpace(forward = false))
        assertEquals(
            listOf("key(mod=0x1, keys=[0x50])", "key(mod=0x0, keys=[])"),
            transport.events,
        )
    }

    @Test
    fun `apple 四指捏合 launchpad 不发送任何报告`() {
        actions.dispatch(TrackpadGesture.Launchpad)

        assertEquals(emptyList<String>(), transport.events)
    }

    @Test
    fun `零档缩放不发送任何报告`() {
        actions.dispatch(TrackpadGesture.Zoom(0))

        assertEquals(emptyList<String>(), transport.events)
    }
}
