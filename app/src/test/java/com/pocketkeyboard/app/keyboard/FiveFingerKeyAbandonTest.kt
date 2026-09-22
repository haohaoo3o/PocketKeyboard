package com.pocketkeyboard.app.keyboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketkeyboard.app.gesture.FiveFingerGate
import com.pocketkeyboard.app.gesture.GestureArbiter
import com.pocketkeyboard.app.gesture.LocalFiveFingerGate
import com.pocketkeyboard.app.gesture.PocketGestureHandler
import com.pocketkeyboard.app.gesture.rememberFiveFingerGate
import com.pocketkeyboard.app.gesture.pocketGestures
import com.pocketkeyboard.app.hid.HidTransport
import com.pocketkeyboard.app.hid.MouseButton
import com.pocketkeyboard.app.ui.AppMode
import com.pocketkeyboard.app.ui.DevicePlatform
import com.pocketkeyboard.app.ui.MainViewModel
import com.pocketkeyboard.app.ui.PairedDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 「五指挥势落在键盘上不再误触」的应用级测试（B1 / B2，Robolectric + Compose 多指注入）。
 *
 * ## 为什么需要这一层
 *
 * 真机实测的根因是**兄弟盲区**：Compose 的 `HitPathTracker` 按指针各自分发，5 根手指分别
 * 落在 5 个不同键帽上时，每颗键的 `PointerEvent.changes` 里只有自己那一根——按键层的
 * 本地 `pressedCount` 永远是 1，`KeyGateState` 永远等不到 `pressedCount >= 5`，门控期满
 * （300ms）后照样激活，用户看到的就是「五指放上键盘仍然误触」。
 *
 * 修法是让按键层读**共享闸门**（父层 `Modifier.pocketGestures` 在 Initial pass 看到的
 * 全局手指数）：`gate.intentDetected`（≥3 指意图）或 `claimed` → 立刻作废，一次报告都不发。
 *
 * 本测试按真实页面结构挂载（`pocketGestures` 在父、`KeyCap` 在子、同一个
 * `LocalFiveFingerGate`），用 Compose 的多指注入按下 5 个不同键，断言：
 *
 * 1. 5 指按下 → **一个按键报告都不发**（防误触的硬保证）；
 * 2. 单指轻点照常发报告（证明测试装置本身能测到按键，5 指的结论不是「反正什么都测不到」）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FiveFingerKeyAbandonTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 记录「按下类」报告的假传输（松键 / 空报告不算误触，单独统计）。 */
    private class RecordingTransport : HidTransport {
        val keyPresses = mutableListOf<Pair<Int, List<Int>>>()
        val allReports = mutableListOf<Pair<Int, List<Int>>>()

        override fun start() = Unit
        override fun stop() = Unit
        override fun sendKeyboardReport(modifiers: Int, keyCodes: ByteArray) {
            val keys = keyCodes.map { it.toInt() and 0xFF }
            allReports += modifiers to keys
            if (keys.isNotEmpty()) keyPresses += modifiers to keys
        }

        override fun sendConsumerUsage(usage: Int) = Unit
        override fun sendMouseMove(dx: Int, dy: Int) = Unit
        override fun sendScroll(dx: Int, dy: Int) = Unit
        override fun sendMouseButton(button: MouseButton, pressed: Boolean) = Unit
    }

    /**
     * 按 MainActivity 的真实结构挂载：根部 `pocketGestures` + 同一个 `LocalFiveFingerGate`，
     * 里面放横屏 / 竖屏共用的 87 键键盘页（每颗键都挂 `pocketKeyGestures`）。
     */
    private fun setContent(transport: HidTransport) {
        val viewModel = MainViewModel().apply {
            setActiveDevice(PairedDevice("AA:BB:CC:DD:EE:FF", "Test PC", DevicePlatform.OTHER))
            setMode(AppMode.KEYBOARD)
        }
        composeRule.setContent {
            KeyboardWithFingerGate(transport = transport, viewModel = viewModel)
        }
        composeRule.waitForIdle()
    }

    @Composable
    private fun KeyboardWithFingerGate(transport: HidTransport, viewModel: MainViewModel) {
        val gate = rememberFiveFingerGate()
        val arbiter = remember { GestureArbiter() }
        val handler = remember { PocketGestureHandler() }
        CompositionLocalProvider(LocalFiveFingerGate provides gate) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 与 MainActivity 一致：五指挥势层挂在最底层，按键层是它的子节点
                    .pocketGestures(handler = handler, arbiter = arbiter, gate = gate),
            ) {
                KeyboardScreen(
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                    transport = transport,
                )
            }
        }
    }

    /** 取若干键帽中心点（相对根节点）。字母键没有无障碍描述，按可见文字找。 */
    private fun centersOf(labels: List<String>): List<Offset> = labels.map { label ->
        composeRule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.center
    }

    @Test
    fun `five fingers on five keys send no key report`() {
        val transport = RecordingTransport()
        setContent(transport)

        val centers = centersOf(listOf("Q", "W", "E", "R", "T"))
        composeRule.onRoot().performTouchInput {
            centers.forEachIndexed { index, center ->
                down(index + 1, center)
            }
        }
        // 5 指按住不动：等过 KEY_PRESS_FIVE_FINGER_GUARD_MS(300ms) 门控。
        // 本地计数永远只有 1（兄弟盲区），能救它们的只有闸门报出的「≥3 指意图」
        composeRule.waitForIdle()

        composeRule.onRoot().performTouchInput {
            up(1)
            up(2)
            up(3)
            up(4)
            up(5)
        }
        composeRule.waitForIdle()

        assertEquals(
            "五指挥势落在 5 个不同键上时一个按键报告都不允许发出（兄弟盲区靠闸门作废）",
            emptyList<Pair<Int, List<Int>>>(),
            transport.keyPresses,
        )
    }

    @Test
    fun `single tap on a key still sends press and release`() {
        val transport = RecordingTransport()
        setContent(transport)

        // 对照组：单指轻点 q —— 照常按下 + 松开（证明上面 5 指的结论不是「测试装置失灵」）
        val q = centersOf(listOf("Q")).single()
        composeRule.onRoot().performTouchInput {
            down(1, q)
        }
        composeRule.waitForIdle()
        composeRule.onRoot().performTouchInput {
            up(1)
        }
        composeRule.waitForIdle()

        assertEquals("单指轻点必须发出按下 + 松开两份报告", 2, transport.allReports.size)
        assertTrue("按下报告必须带 usage", transport.allReports[0].second.isNotEmpty())
        assertTrue("松键报告必须为空", transport.allReports[1].second.isEmpty())
    }
}
