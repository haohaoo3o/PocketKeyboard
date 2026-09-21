package com.pocketkeyboard.app.keyboard

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.pocketkeyboard.app.R
import com.pocketkeyboard.app.hid.HidUsage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * fn 组合键的本机功能文案（fn + 空格 背光 / fn + C 键帽颜色 / fn + S 字号 / fn + V 震感）。
 *
 * 全部在组合期经 `stringResource` 解析后传入：按键回调与 IME 转发回调里没有
 * Composable 上下文，不能直接取字符串资源。
 */
internal class FnComboLabels(
    val brightness: List<String>,
    val keyColor: List<String>,
    val textSize: List<String>,
    val haptic: List<String>,
)

/** 组合期解析 fn 组合 HUD 文案（键盘页与融合页共用同一批资源）。 */
@Composable
internal fun rememberFnComboLabels(): FnComboLabels {
    val brightness = listOf(
        stringResource(R.string.hud_brightness_100),
        stringResource(R.string.hud_brightness_75),
        stringResource(R.string.hud_brightness_50),
        stringResource(R.string.hud_brightness_25),
        stringResource(R.string.hud_brightness_min),
    )
    val keyColor = listOf(
        stringResource(R.string.hud_key_color_white),
        stringResource(R.string.hud_key_color_orange),
        stringResource(R.string.hud_key_color_red),
    )
    val textSize = listOf(
        stringResource(R.string.hud_text_size_small),
        stringResource(R.string.hud_text_size_medium),
        stringResource(R.string.hud_text_size_large),
    )
    val haptic = listOf(
        stringResource(R.string.hud_haptic_off),
        stringResource(R.string.hud_haptic_weak),
        stringResource(R.string.hud_haptic_medium),
        stringResource(R.string.hud_haptic_strong),
    )
    return remember(brightness, keyColor, textSize, haptic) {
        FnComboLabels(brightness, keyColor, textSize, haptic)
    }
}

/**
 * fn 组合的本机功能执行器：横屏 87 键键盘页（[KeyboardScreen]）与竖屏融合页的
 * 系统输入法转发路径（[FusedControlScreen]）**共用同一实现**，保证「fn + 空格/C/S/V」
 * 在两个页面行为一致（都只改本机状态，不发给被控设备）。
 *
 * 组合语义沿用键盘页既有约定：
 * - fn + 空格：循环本机窗口背光档位（[BRIGHTNESS_LEVELS]）；
 * - fn + C / S / V：循环键帽颜色 / 字号 / 震感（写 DataStore 偏好）；
 * - 粘滞的 fn 只生效**一次**组合：命中后调用方负责清除 fn 锁定态
 *   （融合页见 [FusedModifierLatch.withoutFn]，键盘页见 `fnLatched = false`）。
 *
 * @param currentPreferences 读取当前偏好的闭包：循环档位以页面持有的最新偏好为起点，
 *        执行器自己不读 DataStore，避免与页面的 `collectAsStateWithLifecycle` 抢读。
 * @param onHud 命中组合后抛出 HUD 请求（由页面接到 [KeyboardHudLayer] 上展示）。
 */
internal class FnComboActions(
    private val context: Context,
    private val store: KeyboardPreferencesStore,
    private val scope: CoroutineScope,
    private val labels: FnComboLabels,
    private val currentPreferences: () -> KeyboardPreferences,
    private val onHud: (KeyboardHudRequest) -> Unit,
) {

    /** 当前背光档位下标（0 = 100%，见 [BRIGHTNESS_LEVELS]）。 */
    var brightnessLevel: Int = 0
        private set

    /**
     * 执行 fn 组合（键盘页路径：键位动作）。
     *
     * @return true = 命中并已消费（调用方不应再把该键发给被控设备）。
     */
    fun run(action: KeyAction): Boolean = when (action) {
        is KeyAction.Usage ->
            if (action.usage == HidUsage.KEY_SPACE) {
                cycleBrightness()
                true
            } else {
                false
            }

        is KeyAction.Character -> run(action.char)
        else -> false
    }

    /**
     * 执行 fn 组合（融合页路径：系统输入法提交的字符）。
     *
     * @return true = 命中并已消费（调用方不应再转发该字符）。
     */
    fun run(char: Char): Boolean = when (char.lowercaseChar()) {
        ' ' -> {
            cycleBrightness()
            true
        }

        'c' -> {
            cycleKeyCapColor()
            true
        }

        's' -> {
            cycleTextSize()
            true
        }

        'v' -> {
            cycleHapticStrength()
            true
        }

        else -> false
    }

    private fun cycleBrightness() {
        brightnessLevel = (brightnessLevel + 1) % BRIGHTNESS_LEVELS.size
        applyWindowBrightness(context, BRIGHTNESS_LEVELS[brightnessLevel])
        onHud(
            KeyboardHudRequest.Brightness(
                BrightnessHudRequest(
                    level = brightnessLevel,
                    label = labels.brightness[brightnessLevel],
                ),
            ),
        )
    }

    private fun cycleKeyCapColor() {
        val values = KeyCapColor.entries
        val next = values[(currentPreferences().keyCapColor.ordinal + 1) % values.size]
        scope.launch { store.setKeyCapColor(next) }
        onHud(KeyboardHudRequest.Message(MessageHudRequest(labels.keyColor[next.ordinal])))
    }

    private fun cycleTextSize() {
        val values = KeyTextSize.entries
        val next = values[(currentPreferences().keyTextSize.ordinal + 1) % values.size]
        scope.launch { store.setKeyTextSize(next) }
        onHud(KeyboardHudRequest.Message(MessageHudRequest(labels.textSize[next.ordinal])))
    }

    private fun cycleHapticStrength() {
        val values = HapticStrength.entries
        val next = values[(currentPreferences().hapticStrength.ordinal + 1) % values.size]
        scope.launch { store.setHapticStrength(next) }
        onHud(KeyboardHudRequest.Message(MessageHudRequest(labels.haptic[next.ordinal])))
    }
}

/** 把本机窗口亮度设为 [level]（0..1；只影响当前 Activity 窗口，无需 WRITE_SETTINGS）。 */
internal fun applyWindowBrightness(context: Context, level: Float) {
    val activity = context.findActivity() ?: return
    val window = activity.window ?: return
    val attributes = window.attributes
    attributes.screenBrightness = level
    window.attributes = attributes
}

/** 沿 ContextWrapper 链找到宿主 Activity（拿不到返回 null）。 */
internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
