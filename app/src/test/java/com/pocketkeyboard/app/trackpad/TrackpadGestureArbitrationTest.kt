package com.pocketkeyboard.app.trackpad

import android.content.Context
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
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
import com.pocketkeyboard.app.gesture.GestureArbiter
import com.pocketkeyboard.app.gesture.PocketGestureHandler
import com.pocketkeyboard.app.gesture.pocketGestures
import com.pocketkeyboard.app.hid.HidTransport
import com.pocketkeyboard.app.hid.MouseButton
import com.pocketkeyboard.app.keyboard.HapticScale
import com.pocketkeyboard.app.keyboard.HapticStrength
import com.pocketkeyboard.app.ui.DevicePlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowVibrator

/**
 * 触控板手势与五指挥势层**仲裁**的端到端单测（Robolectric + Compose 多指注入）。
 *
 * ## 为什么要在 UI 层测
 *
 * 仲裁的判定逻辑全在 `pointerInput` 协程里（滑动凑指窗口、事件驱动裁决、等待期间的事件缓存），
 * 纯 JVM 单测碰不到；而它一旦退化，表现就是「五指挥势永远不触发」（Bug 4）或者
 * 「触控板手势被拖慢 / 轻点丢失」。因此这里搭出与 MainActivity 完全一致的页面结构
 * （`Modifier.pocketGestures` 挂在父节点、`TrackpadScreen` 作为子节点、共用同一个
 * [GestureArbiter]），注入多指触摸，同时断言两侧的结果：
 *
 * - 五指挥势层：回调有没有被触发（[PocketGestureHandler]）；
 * - 触控板层：HID 报告有没有发送（假 [HidTransport]）。
 *
 * 覆盖的行为：
 * 1. 单指轻点 / 拖动、双指拖动 → 触控板手势照常产出（零劣化）；
 * 2. 5 指一次性落下 → 归五指层，触控板层一个报告都不发；
 * 3. 5 指**慢速**依次落下（间隔 120ms，超过旧实现的 60ms 固定窗口）→ 仍然归五指层
 *    （滑动凑指窗口生效，Bug 4 的回归防线）；
 * 4. 间隔超过续期步长（400ms）→ 五指层放手，手势归触控板层；
 * 5. 按下即拖动 → 五指层立刻放手，指针位移照常产出。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TrackpadGestureArbitrationTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 假传输：按顺序记录所有 HID 调用。 */
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

    /** 与 `rememberTrackpadVibrator()` 完全相同的取法，才能 shadow 到同一个实例。 */
    private fun systemVibrator(): Vibrator {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                .defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    /** 五指挥势层的回调记录（等价于 MainActivity 里的手势回调）。 */
    private val fiveFingerEvents = mutableListOf<String>()

    private fun fiveFingerHandler() = PocketGestureHandler(
        onFiveFingerTap = { fiveFingerEvents += "tap" },
    )

    /** 按 MainActivity 的真实层级挂页面：五指挥势层在父节点，触控板页是它的子节点。 */
    private fun showTrackpadPage() {
        composeRule.setContent {
            val arbiter = remember { GestureArbiter() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pocketGestures(handler = fiveFingerHandler(), arbiter = arbiter),
            ) {
                TrackpadScreen(arbiter = arbiter, transport = transport)
            }
        }
        composeRule.waitForIdle()
    }

    /**
     * 只挂「五指挥势层 + 触控手势修饰符」的最小页面（触控板页与竖屏融合页共用这一条代码路径）。
     *
     * 触觉反馈的测试用它而不是 [TrackpadScreen]：后者会按 DataStore 里的持久化档位
     * `HapticScale.set(...)`，把测试设的强档覆盖回默认中档，而中档走的是
     * `View.performHapticFeedback`（Robolectric 记录不到）。
     */
    private fun showBareGesturePage() {
        composeRule.setContent {
            val arbiter = remember { GestureArbiter() }
            val actions = remember(transport) { TrackpadHidActions(transport) }
            val handler = remember(actions) {
                TrackpadGestureHandler { gesture -> actions.dispatch(gesture) }
            }
            val thresholds = with(LocalDensity.current) {
                TrackpadThresholds(
                    tapSlopPx = TrackpadMetrics.TAP_SLOP.toPx(),
                    swipeDistancePx = TrackpadMetrics.SWIPE_DISTANCE.toPx(),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pocketGestures(handler = fiveFingerHandler(), arbiter = arbiter)
                    .trackpadGestures(
                        platform = DevicePlatform.OTHER,
                        handler = handler,
                        arbiter = arbiter,
                        thresholds = thresholds,
                        clickZonesEnabled = false,
                    ),
            )
        }
        composeRule.waitForIdle()
    }

    // ---------------------------------------------------------------- 触控板手势零劣化

    @Test
    fun `单指轻点发送左键`() {
        showTrackpadPage()

        composeRule.onRoot().performTouchInput {
            // 点在上半屏：避开底部点击带（那块的按下由点击区自己负责）
            down(1, Offset(width * 0.5f, height * 0.25f))
            up(1)
        }
        composeRule.waitForIdle()

        assertTrue("实际发送：${transport.events}", transport.events.contains("button(LEFT, true)"))
        assertTrue("实际发送：${transport.events}", transport.events.contains("button(LEFT, false)"))
        assertEquals("单指轻点不该触发五指挥势", emptyList<String>(), fiveFingerEvents)
    }

    @Test
    fun `单指拖动发送指针位移`() {
        showTrackpadPage()

        composeRule.onRoot().performTouchInput {
            down(1, Offset(width * 0.3f, height * 0.25f))
            moveTo(1, Offset(width * 0.3f + 60f, height * 0.25f))
            up(1)
        }
        composeRule.waitForIdle()

        assertTrue("实际发送：${transport.events}", transport.events.any { it.startsWith("move(") })
        assertEquals("单指拖动不该触发五指挥势", emptyList<String>(), fiveFingerEvents)
    }

    @Test
    fun `双指拖动发送滚轮`() {
        showTrackpadPage()

        composeRule.onRoot().performTouchInput {
            down(1, Offset(width * 0.3f, height * 0.25f))
            down(2, Offset(width * 0.7f, height * 0.25f))
            moveTo(1, Offset(width * 0.3f, height * 0.25f + 40f))
            moveTo(2, Offset(width * 0.7f, height * 0.25f + 40f))
            up(1)
            up(2)
        }
        composeRule.waitForIdle()

        assertTrue("实际发送：${transport.events}", transport.events.any { it.startsWith("scroll(") })
        assertEquals("双指拖动不该触发五指挥势", emptyList<String>(), fiveFingerEvents)
    }

    @Test
    fun `窗口内就结束的轻点仍然判为点击`() {
        showTrackpadPage()

        // 按下与抬起之间没有任何 advanceEventTime：手势在凑指窗口内就结束了。
        // 五指层必须在「所有手指抬起」那一刻立刻放手，触控板层也必须在等待期间
        // 缓存事件，否则这次点击会整段丢失（仲裁改事件驱动后最容易踩的坑）
        composeRule.onRoot().performTouchInput {
            down(1, Offset(width * 0.5f, height * 0.25f))
            up(1)
        }
        composeRule.waitForIdle()

        assertTrue("实际发送：${transport.events}", transport.events.contains("button(LEFT, true)"))
    }

    // ---------------------------------------------------------------- 五指挥势的归属

    /** 五指短按：5 指原地落下再全部抬起（无位移、无间隔）。 */
    private fun androidx.compose.ui.test.TouchInjectionScope.fiveFingerTap(
        fingerGapMillis: Long = 0L,
    ) {
        val y = height * 0.4f
        down(1, Offset(width * 0.04f, y))
        if (fingerGapMillis > 0L) advanceEventTime(fingerGapMillis)
        down(2, Offset(width * 0.28f, y))
        if (fingerGapMillis > 0L) advanceEventTime(fingerGapMillis)
        down(3, Offset(width * 0.50f, y))
        if (fingerGapMillis > 0L) advanceEventTime(fingerGapMillis)
        down(4, Offset(width * 0.72f, y))
        if (fingerGapMillis > 0L) advanceEventTime(fingerGapMillis)
        down(5, Offset(width * 0.96f, y))
        up(1)
        up(2)
        up(3)
        up(4)
        up(5)
    }

    @Test
    fun `五指一次性落下时归五指挥势层`() {
        showTrackpadPage()

        composeRule.onRoot().performTouchInput { fiveFingerTap() }
        composeRule.waitForIdle()

        assertEquals(listOf("tap"), fiveFingerEvents)
        assertEquals("手势归五指层，触控板层不该发报告", emptyList<String>(), transport.events)
    }

    @Test
    fun `五指慢速依次落下仍然归五指挥势层`() {
        showTrackpadPage()

        composeRule.onRoot().performTouchInput {
            // 每根手指间隔 120ms：旧实现的 60ms 固定窗口必然凑不齐（Bug 4 的根因），
            // 滑动凑指窗口（续期 300ms / 上限 800ms）下应当照常识别
            fiveFingerTap(fingerGapMillis = 120L)
        }
        composeRule.waitForIdle()

        assertEquals(listOf("tap"), fiveFingerEvents)
        assertEquals("手势归五指层，触控板层不该发报告", emptyList<String>(), transport.events)
    }

    @Test
    fun `凑指间隔超过续期步长时五指层放手`() {
        showTrackpadPage()

        composeRule.onRoot().performTouchInput {
            // 第一根落下后 400ms 才补第二根：超过续期步长 300ms，五指层早已放手，
            // 本次触摸归触控板层（4 指手势：平台兜底 OTHER → 四指轻点 = Win 键）
            val y = height * 0.4f
            down(1, Offset(width * 0.04f, y))
            advanceEventTime(400)
            down(2, Offset(width * 0.28f, y))
            down(3, Offset(width * 0.50f, y))
            down(4, Offset(width * 0.72f, y))
            up(1)
            up(2)
            up(3)
            up(4)
        }
        composeRule.waitForIdle()

        assertEquals("超过续期步长后不该再凑五指挥势", emptyList<String>(), fiveFingerEvents)
        assertTrue("实际发送：${transport.events}", transport.events.contains("key(mod=0x8, keys=[])"))
    }

    @Test
    fun `按下即拖动时五指层立刻放手`() {
        showTrackpadPage()

        composeRule.onRoot().performTouchInput {
            // 单指按下后立刻拖动 100px：已按下手指位移超过 24dp 且没有新手指落下，
            // 五指层必须立刻释放，触控板手势照常产出指针位移
            down(1, Offset(width * 0.3f, height * 0.25f))
            moveTo(1, Offset(width * 0.3f + 100f, height * 0.25f))
            up(1)
        }
        composeRule.waitForIdle()

        assertEquals("拖动不该触发五指挥势", emptyList<String>(), fiveFingerEvents)
        assertTrue("实际发送：${transport.events}", transport.events.any { it.startsWith("move(") })
    }

    // ---------------------------------------------------------------- 触觉反馈（Bug 7）

    @Test
    fun `手势识别成功给触觉反馈`() {
        // 强档走 Vibrator 单次震动（18ms / 振幅 255），Robolectric 的 ShadowVibrator
        // 会记录下这一次震动时长，因此可以断言「识别成功 → 有触觉反馈」
        HapticScale.set(HapticStrength.STRONG)
        resetVibrationRecord()

        showBareGesturePage()

        composeRule.onRoot().performTouchInput {
            down(1, Offset(width * 0.5f, height * 0.25f))
            up(1)
        }
        composeRule.waitForIdle()

        assertTrue("实际发送：${transport.events}", transport.events.contains("button(LEFT, true)"))
        assertEquals(
            "触控板手势识别成功后应按强档震动一次（18ms）",
            18L,
            shadowOf(systemVibrator()).milliseconds,
        )
    }

    @Test
    fun `指针连续位移不给触觉反馈`() {
        HapticScale.set(HapticStrength.STRONG)
        resetVibrationRecord()

        showBareGesturePage()

        composeRule.onRoot().performTouchInput {
            // 纯拖动：只有 Move（策略上不给反馈），因此整段拖动一次震动都不该有
            down(1, Offset(width * 0.3f, height * 0.25f))
            moveTo(1, Offset(width * 0.3f + 60f, height * 0.25f))
            moveTo(1, Offset(width * 0.3f + 120f, height * 0.25f))
            up(1)
        }
        composeRule.waitForIdle()

        assertTrue("实际发送：${transport.events}", transport.events.any { it.startsWith("move(") })
        assertEquals("纯拖动不该有触觉反馈", 0L, shadowOf(systemVibrator()).milliseconds)
    }

    /** 清空 ShadowVibrator 的全局震动记录（同时把「本机有震动马达」置回 true）。 */
    private fun resetVibrationRecord() {
        ShadowVibrator.reset()
    }

    @Test
    fun `五指横滑不再触发任何手势（复杂手势已移除）`() {
        showTrackpadPage()

        composeRule.onRoot().performTouchInput {
            // 5 指一起向右平移 300px：位移远超短按容差 → 既不是短按、也没有横滑语义
            val y = height * 0.4f
            down(1, Offset(width * 0.04f, y))
            down(2, Offset(width * 0.28f, y))
            down(3, Offset(width * 0.50f, y))
            down(4, Offset(width * 0.72f, y))
            down(5, Offset(width * 0.96f, y))
            moveTo(1, Offset(width * 0.04f + 300f, y))
            moveTo(2, Offset(width * 0.28f + 300f, y))
            moveTo(3, Offset(width * 0.50f + 300f, y))
            moveTo(4, Offset(width * 0.72f + 300f, y))
            moveTo(5, Offset(width * 0.96f + 300f, y))
            up(1)
            up(2)
            up(3)
            up(4)
            up(5)
        }
        composeRule.waitForIdle()

        assertEquals("带位移的五指动作不该触发短按", emptyList<String>(), fiveFingerEvents)
        assertEquals("手势归五指层，触控板层不该发报告", emptyList<String>(), transport.events)
    }
}
