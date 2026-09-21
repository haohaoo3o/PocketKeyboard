package com.pocketkeyboard.app.gesture

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketkeyboard.app.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 五指挥势的**真机**验证（instrumented test，跑在设备上，注入真实 MotionEvent）。
 *
 * 为什么除了 Robolectric 冒烟测试还要这一份：
 * - Robolectric 的 `performTouchInput` 走的是同一条 Compose 注入链路，但事件时间戳 /
 *   InputDispatcher 的行为与真机不同；
 * - Bug 4（五指挥势完全无反应）是**真机实测**发现的，回归防线也必须能在真机上跑：
 *
 * ```
 * ./gradlew installDebug installDebugAndroidTest
 * adb shell pm grant com.pocketkeyboard.app android.permission.BLUETOOTH_CONNECT
 * # ……其余运行时权限同理
 * adb shell am instrument -w \
 *   -e class com.pocketkeyboard.app.gesture.FiveFingerGestureDeviceTest \
 *   com.pocketkeyboard.app.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * 注入的多指手势与真实手指一致：5 根手指**依次**落下（每根间隔 120ms，超过旧实现的
 * 60ms 固定凑指窗口），再一起收拢 / 拉开 / 平移。
 *
 * 断言走 HUD 文案：HUD 只可能由 `PocketGestureHandler` 的三个回调触发
 * （MainActivity 里 `onPinch / onSpread / onSwipe` → `hudState.show(...)`），
 * 因此「HUD 出现」等价于「五指挥势真的被识别了」。
 */
@RunWith(AndroidJUnit4::class)
class FiveFingerGestureDeviceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    /** 从配对页点 dock 进入键盘页（竖屏为融合布局：上半触控板 + 下半 26 键）。 */
    private fun openKeyboardPage() {
        composeRule.waitForIdle()
        composeRule.onNodeWithText("键盘").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Q").onFirst().assertExists()
    }

    /**
     * 五指收缩：5 指横向拉开（间隔 120ms）后向中间收拢 → 切到触控板模式。
     *
     * 竖屏两种 mode 的布局相同，因此用 HUD 文案断言「手势确实被识别」。
     */
    @Test
    fun fiveFingerPinch_showsTrackpadModeHud() {
        openKeyboardPage()

        composeRule.onRoot().performTouchInput {
            val y = centerY
            down(1, Offset(width * 0.05f, y))
            advanceEventTime(120)
            down(2, Offset(width * 0.28f, y))
            advanceEventTime(120)
            down(3, Offset(width * 0.50f, y))
            advanceEventTime(120)
            down(4, Offset(width * 0.72f, y))
            advanceEventTime(120)
            down(5, Offset(width * 0.95f, y))
            moveTo(1, Offset(width * 0.38f, y))
            moveTo(2, Offset(width * 0.44f, y))
            moveTo(3, Offset(width * 0.50f, y))
            moveTo(4, Offset(width * 0.56f, y))
            moveTo(5, Offset(width * 0.62f, y))
            up(1)
            up(2)
            up(3)
            up(4)
            up(5)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("触控板模式").assertExists()
    }

    /** 五指张开：5 指先并拢再拉开 → 切回键盘模式。 */
    @Test
    fun fiveFingerSpread_showsKeyboardModeHud() {
        openKeyboardPage()

        composeRule.onRoot().performTouchInput {
            val y = centerY
            down(1, Offset(width * 0.42f, y))
            advanceEventTime(120)
            down(2, Offset(width * 0.46f, y))
            advanceEventTime(120)
            down(3, Offset(width * 0.50f, y))
            advanceEventTime(120)
            down(4, Offset(width * 0.54f, y))
            advanceEventTime(120)
            down(5, Offset(width * 0.58f, y))
            moveTo(1, Offset(width * 0.05f, y))
            moveTo(2, Offset(width * 0.28f, y))
            moveTo(3, Offset(width * 0.50f, y))
            moveTo(4, Offset(width * 0.72f, y))
            moveTo(5, Offset(width * 0.95f, y))
            up(1)
            up(2)
            up(3)
            up(4)
            up(5)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("键盘模式").assertExists()
    }

    /** 五指整体横滑 → 在已配对设备间循环切换（HUD「已切换至 xxx」+ 覆盖式转场）。 */
    @Test
    fun fiveFingerSwipe_showsDeviceSwitchHud() {
        openKeyboardPage()

        composeRule.onRoot().performTouchInput {
            val y = centerY
            down(1, Offset(width * 0.05f, y))
            advanceEventTime(120)
            down(2, Offset(width * 0.28f, y))
            advanceEventTime(120)
            down(3, Offset(width * 0.50f, y))
            advanceEventTime(120)
            down(4, Offset(width * 0.72f, y))
            advanceEventTime(120)
            down(5, Offset(width * 0.95f, y))
            moveTo(1, Offset(width * 0.05f + 300f, y))
            moveTo(2, Offset(width * 0.28f + 300f, y))
            moveTo(3, Offset(width * 0.50f + 300f, y))
            moveTo(4, Offset(width * 0.72f + 300f, y))
            moveTo(5, Offset(width * 0.95f + 300f, y))
            up(1)
            up(2)
            up(3)
            up(4)
            up(5)
        }
        composeRule.waitForIdle()

        // 有已配对设备 →「已切换至 xxx」；一台都没有 →「暂无已配对设备」。
        // 两条 HUD 都只能由 onSwipe 回调产出，因此任意一条都证明横滑被识别了
        val switched = composeRule
            .onAllNodesWithText("已切换至", substring = true)
            .fetchSemanticsNodes()
        val noDevice = composeRule
            .onAllNodesWithText("暂无已配对设备")
            .fetchSemanticsNodes()
        assertTrue(
            "五指横滑应触发 onSwipe（切换设备或提示没有已配对设备），" +
                "实际两种 HUD 都没出现",
            switched.isNotEmpty() || noDevice.isNotEmpty(),
        )
    }

    /**
     * 触控板手势零劣化：单指轻点、单指拖动、双指滚动、三指上滑，页面必须照常响应。
     *
     * 真机上 HID 报告发去哪取决于对端是否在线，因此这里不断言报告内容，而是断言
     * 「手势没有把页面切走、没有崩溃」——具体的报告发送序列由
     * [com.pocketkeyboard.app.trackpad.TrackpadGestureArbitrationTest] 用假 transport 覆盖。
     */
    @Test
    fun trackpadGesturesStillWorkAfterFiveFingerArbitration() {
        openKeyboardPage()

        // 单指轻点（点在触控区上半，避开底部左右点击带）
        composeRule.onRoot().performTouchInput {
            down(1, Offset(width * 0.5f, height * 0.2f))
            up(1)
        }
        composeRule.waitForIdle()

        // 单指拖动
        composeRule.onRoot().performTouchInput {
            down(1, Offset(width * 0.3f, height * 0.2f))
            moveTo(1, Offset(width * 0.3f + 120f, height * 0.2f))
            up(1)
        }
        composeRule.waitForIdle()

        // 双指滚动
        composeRule.onRoot().performTouchInput {
            down(1, Offset(width * 0.3f, height * 0.2f))
            down(2, Offset(width * 0.7f, height * 0.2f))
            moveTo(1, Offset(width * 0.3f, height * 0.2f + 80f))
            moveTo(2, Offset(width * 0.7f, height * 0.2f + 80f))
            up(1)
            up(2)
        }
        composeRule.waitForIdle()

        // 三指上滑（Windows 模式 = 任务视图）
        composeRule.onRoot().performTouchInput {
            down(1, Offset(width * 0.3f, height * 0.5f))
            down(2, Offset(width * 0.5f, height * 0.5f))
            down(3, Offset(width * 0.7f, height * 0.5f))
            moveTo(1, Offset(width * 0.3f, height * 0.5f - 200f))
            moveTo(2, Offset(width * 0.5f, height * 0.5f - 200f))
            moveTo(3, Offset(width * 0.7f, height * 0.5f - 200f))
            up(1)
            up(2)
            up(3)
        }
        composeRule.waitForIdle()

        // 一路手势做完，页面仍是融合布局的键盘页（没有被误判成五指挥势切走、没有崩溃）
        composeRule.onAllNodesWithText("Q").onFirst().assertExists()
        composeRule.onNodeWithContentDescription("打开或收起数字小键盘").assertExists()
    }
}
