package com.pocketkeyboard.app.gesture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 五指挥意图闸门的单测（问题 3a / 3b 的 UI 联动）。
 *
 * 闸门是手势协程（`pointerInput`）与 Compose UI 之间唯一的传话通道：
 * 手势层在凑指窗口里看到 ≥[GestureConstants.FIVE_FINGER_INTENT_FINGER_COUNT] 指就置位，
 * UI 层据此收起系统输入法 / 拒绝聚焦弹键盘；一次手势结束（所有手指抬起）后复位。
 */
/**
 * 注意：本测试**不加** `@RunWith(AndroidJUnit4::class)` / `@Config`——`FiveFingerGate`
 * 只用 `mutableStateOf`，不需要 Android 框架，跑 Robolectric 只会白建一个沙箱、
 * 给同 JVM 里的 Compose 冒烟测试添内存压力（实测会让后续 Compose 测试在 60s 内
 * 拿不到 idle）。
 */
class FiveFingerGateTest {

    private val gate = FiveFingerGate()

    @Test
    fun `初始状态不该让位`() {
        assertFalse(gate.shouldYieldToFiveFinger)
    }

    @Test
    fun `两手按下还不算有五指挥势意图`() {
        // 双指是触控板上的常规手势（滚动 / 缩放），不能碰一下就收键盘
        gate.observeGather(fingerCount = 2, claimed = false)
        assertFalse(gate.shouldYieldToFiveFinger)
    }

    @Test
    fun `三指按下即有五指挥势意图`() {
        gate.observeGather(fingerCount = 3, claimed = false)
        assertTrue(gate.shouldYieldToFiveFinger)
    }

    @Test
    fun `凑满五指数值回退也要保持让位`() {
        // 手势层先报 5 指（claimed = true），随后某一帧只看到 3 指在动：
        // 手指数回调式下降不能把已经置位的意图撤掉，否则输入法会被重新弹出来
        gate.observeGather(fingerCount = 5, claimed = true)
        gate.observeGather(fingerCount = 3, claimed = true)
        assertTrue(gate.shouldYieldToFiveFinger)
    }

    @Test
    fun `手势结束后复位`() {
        gate.observeGather(fingerCount = 5, claimed = true)
        assertTrue(gate.shouldYieldToFiveFinger)
        gate.reset()
        assertFalse("所有手指抬起后必须复位，否则下一轮手势一直处于让位态", gate.shouldYieldToFiveFinger)
    }
}
