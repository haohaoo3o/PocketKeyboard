package com.pocketkeyboard.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 五指短按的 UI 冒烟测试（Robolectric + Compose 多指注入，JVM 可复现）。
 *
 * 产品决策（2026-09-23）：五指手势**只保留短按一种**——5 指凑齐后快速原地抬起
 * → 返回键盘页面；收缩 / 张开 / 横滑那套复杂几何判定已整体移除（真机太难触发、
 * 互相误判，用户明确要求「不要搞复杂手势操作了」）。
 *
 * - 竖屏：触控板模式下五指短按 → 回键盘模式（HUD「键盘模式」为证）；
 *   键盘模式下五指短按是幂等空操作（不弹 HUD）；
 * - 横屏：全屏触控板下五指短按 → 切回 87 键键盘（esc 出现）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FiveFingerGestureSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun portrait_fiveFingerTap_returnsToKeyboardWithHud() {
        composeRule.waitForIdle()

        // 配对页 dock 常显，直接点「触控板」进入触控板模式
        composeRule.onNodeWithText("触控板").performClick()
        composeRule.waitForIdle()

        // 五指短按 → 返回键盘页面（HUD 文案是手势识别成功的直接证据）
        composeRule.fiveFingerTap()
        composeRule.onNodeWithText("键盘模式").assertExists()
    }

    @Test
    fun portrait_fiveFingerTapOnKeyboard_isNoOp() {
        composeRule.waitForIdle()

        composeRule.onNodeWithText("键盘").performClick()
        composeRule.waitForIdle()

        // 已在键盘页面：五指短按是幂等空操作，不弹 HUD
        composeRule.fiveFingerTap()
        composeRule.onAllNodesWithText("键盘模式").assertCountEquals(0)
    }

    /**
     * 横屏：全屏触控板下做一次五指短按。
     *
     * 横屏两页视觉一目了然：TRACKPAD = 全屏触控板（123 开关在、esc 不在），
     * KEYBOARD = 全屏 87 键（esc 在）。短按后 esc 出现即证明切回了键盘页。
     */
    @RunWith(AndroidJUnit4::class)
    @Config(sdk = [34], qualifiers = "land")
    class Landscape {

        @get:Rule
        val composeRule = createAndroidComposeRule<MainActivity>()

        @Test
        fun landscape_fiveFingerTap_returnsFromTrackpadToTklKeyboard() {
            composeRule.waitForIdle()

            // 横屏配对页 dock 常显，点「触控板」进入全屏触控板
            composeRule.onNodeWithText("触控板").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("123").assertExists()
            composeRule.onAllNodesWithText("esc").assertCountEquals(0)

            // 五指短按 → 切回 87 键
            composeRule.fiveFingerTap()
            composeRule.waitForIdle()
            composeRule.onAllNodesWithText("esc").onFirst().assertExists()
        }
    }
}

/**
 * 五指短按的注入手势：5 指原地落下后立刻全部抬起（无位移）。
 *
 * 全部 down / up 在同一个 `performTouchInput` 块里连续注入、事件时间不动，
 * 因此「凑齐 → 抬起」时长为 0，落在
 * [com.pocketkeyboard.app.gesture.GestureConstants.FIVE_FINGER_TAP_MAX_MS] 的短按窗口内。
 */
private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.fiveFingerTap() {
    onRoot().performTouchInput {
        val y = centerY
        down(1, Offset(width * 0.04f, y))
        down(2, Offset(width * 0.28f, y))
        down(3, Offset(width * 0.50f, y))
        down(4, Offset(width * 0.72f, y))
        down(5, Offset(width * 0.96f, y))
        up(1)
        up(2)
        up(3)
        up(4)
        up(5)
    }
    waitForIdle()
}
