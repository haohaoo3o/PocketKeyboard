package com.pocketkeyboard.app.trackpad

import com.pocketkeyboard.app.hid.HidModifier
import com.pocketkeyboard.app.hid.HidTransport
import com.pocketkeyboard.app.hid.HidUsage
import com.pocketkeyboard.app.hid.HidUsageMapper
import kotlin.math.abs

/**
 * 把 [TrackpadGesture] 翻译成 [HidTransport] 契约调用。
 *
 * ## 为什么单独一层
 *
 * 手势引擎（`TrackpadGestures.kt` / `TrackpadPointerInput.kt`）是纯 Kotlin 的，
 * 不依赖 transport；本类把所有「怎么发」的知识收在一处，于是：
 * - 手势逻辑可以在 JVM 单测里用一个假 transport 完整驱动；
 * - 「组合手势必须先按修饰键、发完再松开」这条规则只有一处实现，不会在三处手势里各写一遍。
 *
 * ## 修饰键的处理规则（重要）
 *
 * 引导（boot）键盘报告没有「修饰键按下 / 松开」的独立事件，修饰键只是报告里的一个位图，
 * 因此任何「ctrl+滚轮」「Win+Tab」这样的组合都必须走同一条路：
 *
 * ```
 * sendKeyboardReport(修饰键位图, [普通键])   // 修饰键按下 + 普通键按下（同一份报告）
 * sendKeyboardReport(0, [])                  // 全部松开
 * ```
 *
 * 即「先按下修饰键 → 发组合内容 → 立刻松开」，修饰键绝不长时间保持按下，
 * 避免主机把后续无关输入也当成组合键。
 */
internal class TrackpadHidActions(
    private val transport: HidTransport,
) {

    /** 空按键数组（松开全部普通键）。 */
    private val noKeys = ByteArray(0)

    /** 发送一个手势；无法映射的手势（如 Apple 四指捏合）静默忽略。 */
    fun dispatch(gesture: TrackpadGesture) {
        when (gesture) {
            // ---- 指针与滚轮：直接走契约接口 ----
            is TrackpadGesture.Move -> transport.sendMouseMove(gesture.dx, gesture.dy)

            is TrackpadGesture.Scroll -> transport.sendScroll(gesture.dx, gesture.dy)

            // ---- 点击：按下后立刻松开（tap-to-click / 双指点击）----
            is TrackpadGesture.Click -> {
                transport.sendMouseButton(gesture.button, true)
                transport.sendMouseButton(gesture.button, false)
            }

            // ---- 捏合 / 旋转 → ctrl + 滚轮 ----
            is TrackpadGesture.Zoom -> sendZoom(gesture.notches)

            // ---- Windows 平台级手势 ----
            TrackpadGesture.WinKey -> sendWithModifiers(HidModifier.LEFT_GUI, 0)

            TrackpadGesture.TaskView ->
                // 三指上滑 = 任务视图（Win + Tab）
                sendWithModifiers(HidModifier.LEFT_GUI, HidUsage.KEY_TAB)

            TrackpadGesture.ShowDesktop ->
                // 三指下滑 = 显示桌面（Win + D）
                sendWithModifiers(HidModifier.LEFT_GUI, KEY_D)

            is TrackpadGesture.SwitchApp ->
                // 三指左右滑 = 切换应用（Alt + Tab；向左 = Alt + Shift + Tab 反向切换）
                sendWithModifiers(
                    modifiers = if (gesture.forward) {
                        HidModifier.LEFT_ALT
                    } else {
                        HidModifier.LEFT_ALT or HidModifier.LEFT_SHIFT
                    },
                    key = HidUsage.KEY_TAB,
                )

            // ---- Apple 平台级手势 ----
            TrackpadGesture.MissionControl ->
                // 三指上滑 = Mission Control（ctrl + ↑）
                sendWithModifiers(HidModifier.LEFT_CTRL, HidUsage.KEY_UP_ARROW)

            is TrackpadGesture.SwitchSpace ->
                // 三指左右滑 = 切换全屏空间（ctrl + ← / →）
                sendWithModifiers(
                    modifiers = HidModifier.LEFT_CTRL,
                    key = if (gesture.forward) HidUsage.KEY_RIGHT_ARROW else HidUsage.KEY_LEFT_ARROW,
                )

            // 四指捏合 = Launchpad：没有等价的 HID 用法，见 TrackpadGesture.Launchpad 注释
            TrackpadGesture.Launchpad -> Unit
        }
    }

    /** ctrl + 滚轮：先按 LeftCtrl，滚轮发完立刻松开。 */
    private fun sendZoom(notches: Int) {
        if (notches == 0) return
        // 修饰键按下
        transport.sendKeyboardReport(HidModifier.LEFT_CTRL, noKeys)
        val unit = if (notches > 0) -ZOOM_WHEEL_UNIT else ZOOM_WHEEL_UNIT
        repeat(abs(notches)) {
            // 放大 = 滚轮向上（dy 负），缩小 = 滚轮向下（dy 正）
            transport.sendScroll(0, unit)
        }
        // 修饰键松开
        transport.sendKeyboardReport(HidModifier.NONE, noKeys)
    }

    /**
     * 「修饰键 + 单个普通键」组合，发完立刻全部松开。
     *
     * @param modifiers 修饰键位图（见 [HidModifier]）
     * @param key Keyboard Page (0x07) usage；0 表示只按修饰键本身（如 Win 键）
     */
    private fun sendWithModifiers(modifiers: Int, key: Int) {
        val keys = if (key == 0) noKeys else byteArrayOf(key.toByte())
        transport.sendKeyboardReport(modifiers, keys)
        transport.sendKeyboardReport(HidModifier.NONE, noKeys)
    }

    private companion object {

        /** 一格 ctrl+滚轮 的滚轮量（HID 滚轮 1 格 = 1 个单位）。 */
        const val ZOOM_WHEEL_UNIT = 1

        /**
         * Keyboard Page usage：D（`HidUsage` 只列了高频常量，没有 D；
         * 这里按字符映射表取，和键盘页输入字符走的是同一张表）。
         */
        val KEY_D: Int = HidUsageMapper.CHAR_TO_USAGE.getValue('d')
    }
}
