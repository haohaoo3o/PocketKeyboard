package com.pocketkeyboard.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 横屏布局分支的 UI 冒烟测试（Robolectric + Compose，JVM 可复现）。
 *
 * 用 `@Config(qualifiers = "land")` 让 Activity 直接以横屏配置启动
 * （manifest 声明 configChanges 含 orientation，真机上旋转不会重建 Activity，
 * 这里以配置限定符等价验证同一分支）。
 *
 * 覆盖：横屏 KEYBOARD mode = 全屏 87 键 TKL；横屏 TRACKPAD mode = 全屏触控板；
 * 横屏下 dock 同样默认隐藏、可单指边缘内滑唤出、切模式后再次自动隐藏。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "land")
class AppLandscapeSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun landscapeShowsFullscreenTklAndFullscreenTrackpad() {
        composeRule.waitForIdle()

        // 配对页 dock 常显，直接点「键盘」进入横屏全屏 87 键布局
        composeRule.onNodeWithText("键盘").performClick()
        composeRule.waitForIdle()

        // 横屏 KEYBOARD mode = 全屏 87 键 TKL：esc 存在（融合布局里没有 esc）
        composeRule.onAllNodesWithText("esc").onFirst().assertExists()
        // 87 键字母同样大写显示
        composeRule.onAllNodesWithText("Q").onFirst().assertExists()

        // dock 在键盘页默认自动隐藏
        composeRule.onAllNodesWithText("触控板").assertCountEquals(0)

        // 单指从屏幕左缘向内滑过 24dp → dock 滑入
        swipeFromLeftEdgeToShowDock()
        composeRule.onNodeWithText("触控板").assertExists()

        // 切到触控板页：横屏 = 全屏触控板（87 键 esc 消失，dock 再次自动隐藏）
        composeRule.onNodeWithText("触控板").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("esc").assertCountEquals(0)
        composeRule.onAllNodesWithText("触控板").assertCountEquals(0)
        // 全屏触控板仍保留右上角「123」小键盘开关
        composeRule.onNodeWithContentDescription("打开或收起数字小键盘").assertExists()

        // 回到配对页：dock 常显
        swipeFromLeftEdgeToShowDock()
        composeRule.onNodeWithText("配对").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("CONTROL").assertExists()
        composeRule.onNodeWithText("触控板").assertExists()
    }

    /**
     * 单指从屏幕左缘向内滑过 24dp：唤出 dock。
     *
     * 与竖屏版同参数：x = 0（左缘）出发、水平向右 160px，越过 24dp 激活带与触发距离；
     * 落在激活带内的按下被 dock 激活层接管，不会被 87 键键盘的按键消费掉。
     */
    private fun swipeFromLeftEdgeToShowDock() {
        composeRule.onRoot().performTouchInput {
            val y = centerY
            swipe(
                start = Offset(0f, y),
                end = Offset(160f, y),
                durationMillis = 200,
            )
        }
        composeRule.waitForIdle()
    }
}
