package com.pocketkeyboard.app.gesture

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketkeyboard.app.hid.HidTransport
import com.pocketkeyboard.app.hid.MouseButton
import com.pocketkeyboard.app.trackpad.TrackpadGestureHandler
import com.pocketkeyboard.app.trackpad.TrackpadMetrics
import com.pocketkeyboard.app.trackpad.TrackpadThresholds
import com.pocketkeyboard.app.trackpad.trackpadGestures
import com.pocketkeyboard.app.ui.DevicePlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 「一次触摸序列结束（所有指针抬起）→ 状态必须立刻复位」的回归测试（问题 3a / 2 根因）。
 *
 * ## 真机症状与根因的对应
 *
 * v5 的私有 `awaitAllPointersUp`（PocketGestures.kt）**先等待后检查**：所有指针在
 * **同一个事件**里抬起（同帧抬指、或 Compose 对 ACTION_CANCEL 合成的全抬起事件）
 * 时它不会返回，而是挂到**下一次触摸结束**才复位 `FiveFingerGate` / `GestureArbiter`：
 *
 * - 陈旧的 `gate.pendingFingers ≥ 3 / claimed = true` 覆盖下一次触摸 → 输入区
 *   `clickable` 被门控拦截、`LaunchedEffect` 分支①压制自动弹出 → 症状 3a；
 * - 五指层卡在上一轮收尾、不再跑凑指逻辑 → 不会 `releaseToTrackpad()` → 触控板层
 *   每次都等满 1500ms 安全超时才有裁决 → 症状 2（连续手势交替失灵）。
 *
 * 框架自带的 `androidx.compose.foundation.gestures.awaitAllPointersUp` 是
 * **先查 currentEvent 再等待**，本文件用同样的注入序列锁住修复后的行为：
 * 每条断言都必须在触摸序列抬起后的**同一轮 waitForIdle** 内成立（不能靠超时兜底）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PocketGestureSequenceResetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var gate: FiveFingerGate
    private lateinit var arbiter: GestureArbiter
    private val fiveFingerEvents = mutableListOf<String>()

    /** 假传输：只记录触控板层产出的鼠标事件。 */
    private class RecordingTransport : HidTransport {
        val events = mutableListOf<String>()
        override fun start() = Unit
        override fun stop() = Unit
        override fun sendKeyboardReport(modifiers: Int, keyCodes: ByteArray) = Unit
        override fun sendConsumerUsage(usage: Int) = Unit
        override fun sendMouseMove(dx: Int, dy: Int) {
            events += "move($dx, $dy)"
        }

        override fun sendScroll(dx: Int, dy: Int) = Unit
        override fun sendMouseButton(button: MouseButton, pressed: Boolean) {
            events += "button($button, $pressed)"
        }
    }

    private val transport = RecordingTransport()

    /**
     * 与 MainActivity 一致的层级：五指层挂父节点；触控板层（可选）作为子节点共用仲裁器。
     * 闸门实例在测试侧持有，抬起后直接读它的状态断言。
     */
    private fun showPage(withTrackpad: Boolean) {
        composeRule.setContent {
            val density = LocalDensity.current
            gate = remember { FiveFingerGate() }
            arbiter = remember { GestureArbiter() }
            val handler = remember {
                PocketGestureHandler(
                    onPinch = { fiveFingerEvents += "pinch" },
                    onSpread = { fiveFingerEvents += "spread" },
                    onSwipe = { dir -> fiveFingerEvents += "swipe($dir)" },
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pocketGestures(handler = handler, arbiter = arbiter, gate = gate),
            ) {
                if (withTrackpad) {
                    val actions = remember(transport) {
                        com.pocketkeyboard.app.trackpad.TrackpadHidActions(transport)
                    }
                    val trackpadHandler = remember(actions) {
                        TrackpadGestureHandler { gesture -> actions.dispatch(gesture) }
                    }
                    val thresholds = with(density) {
                        TrackpadThresholds(
                            tapSlopPx = TrackpadMetrics.TAP_SLOP.toPx(),
                            swipeDistancePx = TrackpadMetrics.SWIPE_DISTANCE.toPx(),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .trackpadGestures(
                                platform = DevicePlatform.OTHER,
                                handler = trackpadHandler,
                                arbiter = arbiter,
                                thresholds = thresholds,
                                clickZonesEnabled = false,
                            ),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        assertTrue("gate 未初始化（组合未完成）", ::gate.isInitialized)
    }

    /**
     * 症状 3a 的直接回归：3 指（五指意图阈值）按下→闸门置位；**同帧全部抬起**后，
     * 同一轮 idle 内闸门必须已经复位——否则输入区 clickable 的让位门控会把下一次
     * 点击拦截掉（真机上「点输入区没反应 / 自动弹出被压住」的根因）。
     */
    @Test
    fun `三指同帧抬起后闸门立刻复位不再让位`() {
        showPage(withTrackpad = false)

        composeRule.onRoot().performTouchInput {
            down(1, Offset(width * 0.3f, height * 0.3f))
            down(2, Offset(width * 0.5f, height * 0.3f))
            down(3, Offset(width * 0.7f, height * 0.3f))
        }
        composeRule.waitForIdle()
        assertTrue(
            "按下 3 指后应处于让位态（这是手势期间收起输入法的依据）",
            gate.shouldYieldToFiveFinger,
        )

        composeRule.onRoot().performTouchInput {
            up(1)
            up(2)
            up(3)
        }
        composeRule.waitForIdle()
        assertFalse(
            "同帧抬起后闸门必须在同一轮 idle 内复位；陈旧的让位态会拦截下一次输入区点击（症状 3a）",
            gate.shouldYieldToFiveFinger,
        )
        assertEquals(0, gate.pendingFingers)
        assertFalse(gate.claimed)
        assertFalse(
            "五指层不得把陈旧的接管声明带进下一次触摸（症状 2 的仲裁卡死来源）",
            arbiter.isFiveFingerActive,
        )
    }

    /**
     * 症状 2 的直接回归：连续两次单指轻点，**第二次**不能等待仲裁安全超时
     * （1500ms）——第一次轻点结束时五指层若把 `awaitAllPointersUp` 挂到「下一个
     * 触摸序列」上，它就不会为第二次触摸跑凑指/放手逻辑，触控板层只能靠超时兜底。
     * 断言与第一次一样在同一轮 waitForIdle 内完成。
     */
    @Test
    fun `连续两次轻点第二次也立即产出点击`() {
        showPage(withTrackpad = true)

        composeRule.onRoot().performTouchInput {
            down(1, Offset(width * 0.5f, height * 0.2f))
            up(1)
        }
        composeRule.waitForIdle()
        assertTrue(
            "第一次轻点应立即出左键按下：${transport.events}",
            transport.events.contains("button(LEFT, true)"),
        )

        transport.events.clear()
        composeRule.onRoot().performTouchInput {
            down(1, Offset(width * 0.5f, height * 0.2f))
            up(1)
        }
        composeRule.waitForIdle()
        assertTrue(
            "第二次轻点必须同样立即出点击（等满 1.5s 安全超时 = 真机上手势无响应，症状 2）：" +
                transport.events,
            transport.events.contains("button(LEFT, true)"),
        )
    }
}
