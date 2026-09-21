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
 * 五指挥势的 UI 冒烟测试（Robolectric + Compose 多指注入，JVM 可复现）。
 *
 * 本轮竖屏融合 / 横屏全屏改造把五指挥势层（`Modifier.pocketGestures`）继续挂在
 * 页面容器最底层（MainActivity 里承载 `AnimatedContent` 的 Box），因此两个方向下
 * 手势都应生效。Compose 测试 API 1.7 起支持多指注入（`down(pointerId, …)`），
 * 这里用它按真实时序凑满 5 指（仲裁窗口 60ms 内）做一次收缩 / 张开：
 *
 * - 竖屏：五指收缩 → HUD「触控板模式」；再五指张开 → HUD「键盘模式」；
 * - 横屏：87 键全屏下五指收缩 → 模式切到 TRACKPAD（esc 消失、123 开关出现），
 *   证明横屏五指挥势仍然生效。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FiveFingerGestureSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun portrait_fiveFingerPinchAndSpread_switchModeWithHud() {
        composeRule.waitForIdle()

        // 配对页 dock 常显，直接点「键盘」进入竖屏融合布局
        composeRule.onNodeWithText("键盘").performClick()
        composeRule.waitForIdle()

        // 五指收缩 → 触控板模式（HUD 文案是手势识别成功的直接证据）
        fiveFingerPinch()
        composeRule.onNodeWithText("触控板模式").assertExists()

        // 五指张开 → 键盘模式
        fiveFingerSpread()
        composeRule.onNodeWithText("键盘模式").assertExists()
    }

    /**
     * 横屏：87 键全屏键盘下做一次五指收缩。
     *
     * 竖屏两种 mode 视觉相同（都是融合布局），横屏则一目了然：
     * KEYBOARD = 全屏 87 键（esc 在），TRACKPAD = 全屏触控板（esc 不在、123 开关在）。
     */
    @RunWith(AndroidJUnit4::class)
    @Config(sdk = [34], qualifiers = "land")
    class Landscape {

        @get:Rule
        val composeRule = createAndroidComposeRule<MainActivity>()

        @Test
        fun landscape_fiveFingerPinch_switchesTklToFullscreenTrackpad() {
            composeRule.waitForIdle()

            // 横屏配对页 dock 常显，点「键盘」进入全屏 87 键
            composeRule.onNodeWithText("键盘").performClick()
            composeRule.waitForIdle()
            composeRule.onAllNodesWithText("esc").onFirst().assertExists()

            // 五指收缩 → 切到 TRACKPAD：87 键消失、全屏触控板（123 开关）出现
            fiveFingerPinch()
            composeRule.waitForIdle()
            composeRule.onAllNodesWithText("esc").assertCountEquals(0)
            composeRule.onNodeWithText("触控板模式").assertExists()
        }

        private fun fiveFingerPinch() = with(composeRule) {
            onRoot().performTouchInput {
                val y = centerY
                // 5 指横向拉开（一次性全部落下：必须在 60ms 仲裁窗口内凑满）
                down(1, Offset(width * 0.04f, y))
                down(2, Offset(width * 0.28f, y))
                down(3, Offset(width * 0.50f, y))
                down(4, Offset(width * 0.72f, y))
                down(5, Offset(width * 0.96f, y))
                // 向中间收拢：平均间距缩到初始的 30% 以下即判定为收缩
                moveTo(1, Offset(width * 0.40f, y))
                moveTo(2, Offset(width * 0.45f, y))
                moveTo(3, Offset(width * 0.50f, y))
                moveTo(4, Offset(width * 0.55f, y))
                moveTo(5, Offset(width * 0.60f, y))
                up(1)
                up(2)
                up(3)
                up(4)
                up(5)
            }
            waitForIdle()
        }
    }

    /**
     * 五指收缩：5 指横向拉开后向中间收拢（间距缩到初始 30% 以下 → onPinch）。
     *
     * 全部 down 在同一个 `performTouchInput` 块里连续注入，凑满 5 指的时间落在
     * [com.pocketkeyboard.app.gesture.GestureConstants.ARBITRATION_WINDOW_MS]
     * 仲裁窗口内，手势归五指挥势层而不是触控板层。
     */
    private fun fiveFingerPinch() = with(composeRule) {
        onRoot().performTouchInput {
            val y = centerY
            down(1, Offset(width * 0.04f, y))
            down(2, Offset(width * 0.28f, y))
            down(3, Offset(width * 0.50f, y))
            down(4, Offset(width * 0.72f, y))
            down(5, Offset(width * 0.96f, y))
            moveTo(1, Offset(width * 0.40f, y))
            moveTo(2, Offset(width * 0.45f, y))
            moveTo(3, Offset(width * 0.50f, y))
            moveTo(4, Offset(width * 0.55f, y))
            moveTo(5, Offset(width * 0.60f, y))
            up(1)
            up(2)
            up(3)
            up(4)
            up(5)
        }
        waitForIdle()
    }

    /** 五指张开：5 指先并拢再拉开（间距放大到初始 130% 以上 → onSpread）。 */
    private fun fiveFingerSpread() = with(composeRule) {
        onRoot().performTouchInput {
            val y = centerY
            down(1, Offset(width * 0.42f, y))
            down(2, Offset(width * 0.46f, y))
            down(3, Offset(width * 0.50f, y))
            down(4, Offset(width * 0.54f, y))
            down(5, Offset(width * 0.58f, y))
            moveTo(1, Offset(width * 0.04f, y))
            moveTo(2, Offset(width * 0.28f, y))
            moveTo(3, Offset(width * 0.50f, y))
            moveTo(4, Offset(width * 0.72f, y))
            moveTo(5, Offset(width * 0.96f, y))
            up(1)
            up(2)
            up(3)
            up(4)
            up(5)
        }
        waitForIdle()
    }
}
