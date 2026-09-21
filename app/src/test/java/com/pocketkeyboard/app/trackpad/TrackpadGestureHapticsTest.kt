package com.pocketkeyboard.app.trackpad

import com.pocketkeyboard.app.hid.MouseButton
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TrackpadGestureHaptics] 与反馈策略的纯 JVM 单测。
 *
 * 策略本身（哪些手势给反馈）是纯函数；「滚动只在第一拍反馈」需要状态，因此把反馈动作
 * 换成一个计数 lambda 来断言次数，不碰 Android 的 Vibrator。
 */
class TrackpadGestureHapticsTest {

    @Test
    fun `点击与系统手势都给反馈`() {
        val feedbacks = mutableListOf<String>()
        val haptics = TrackpadGestureHaptics { feedbacks += "tick" }

        haptics.onGesture(TrackpadGesture.Click(MouseButton.LEFT))
        haptics.onGesture(TrackpadGesture.Click(MouseButton.RIGHT))
        haptics.onGesture(TrackpadGesture.Zoom(1))
        haptics.onGesture(TrackpadGesture.WinKey)
        haptics.onGesture(TrackpadGesture.TaskView)
        haptics.onGesture(TrackpadGesture.ShowDesktop)
        haptics.onGesture(TrackpadGesture.MissionControl)
        haptics.onGesture(TrackpadGesture.SwitchApp(forward = true))
        haptics.onGesture(TrackpadGesture.SwitchSpace(forward = false))

        assertEquals(9, feedbacks.size)
    }

    @Test
    fun `指针连续位移不给反馈`() {
        val feedbacks = mutableListOf<String>()
        val haptics = TrackpadGestureHaptics { feedbacks += "tick" }

        haptics.onGesture(TrackpadGesture.Move(10, 0))
        haptics.onGesture(TrackpadGesture.Move(0, 10))
        haptics.onGesture(TrackpadGesture.Move(-30, 30))

        assertEquals(0, feedbacks.size)
    }

    @Test
    fun `双指滚动只在开始时反馈一次`() {
        val feedbacks = mutableListOf<String>()
        val haptics = TrackpadGestureHaptics { feedbacks += "tick" }

        // 一次滚动手势会产出很多拍 Scroll，只在第一拍反馈
        repeat(5) { haptics.onGesture(TrackpadGesture.Scroll(0, 1)) }

        assertEquals(1, feedbacks.size)
    }

    @Test
    fun `下一次手势重新开始滚动反馈`() {
        val feedbacks = mutableListOf<String>()
        val haptics = TrackpadGestureHaptics { feedbacks += "tick" }

        haptics.onGesture(TrackpadGesture.Scroll(0, 1))
        haptics.onGesture(TrackpadGesture.Scroll(0, 1))
        // 新手势：新实例（Modifier.trackpadGestures 每轮 awaitEachGesture 新建一个）
        TrackpadGestureHaptics { feedbacks += "tick" }
            .onGesture(TrackpadGesture.Scroll(0, 1))

        assertEquals(2, feedbacks.size)
    }

    @Test
    fun `缩放多拍每档都反馈`() {
        val feedbacks = mutableListOf<String>()
        val haptics = TrackpadGestureHaptics { feedbacks += "tick" }

        haptics.onGesture(TrackpadGesture.Zoom(1))
        haptics.onGesture(TrackpadGesture.Zoom(-1))

        assertEquals(2, feedbacks.size)
    }
}
