package com.pocketkeyboard.app.keyboard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 震感强度共享单例（键盘页 ↔ 触控板页同步）。
 *
 * 为什么用单例而不是塞进 `MainViewModel`：`MainViewModel` 是跨模块契约
 * （`pinCode / pairedDevices / activeDevice / mode` 四个字段禁止改名、禁止改签名），
 * 触觉档位属于键盘 / 触控板两个页面之间的私有约定，放契约里会污染其他模块。
 *
 * 用法：
 * - 键盘页：偏好（DataStore）变化时 `HapticScale.set(...)` 推送；
 * - 触控板页：读 `HapticScale.strength.value` 决定一次滑动反馈的强弱。
 */
object HapticScale {

    private val _strength = MutableStateFlow(HapticStrength.MEDIUM)

    /** 当前震感档位；默认「中」（即系统标准 KEYBOARD_TAP 触感）。 */
    val strength: StateFlow<HapticStrength> = _strength.asStateFlow()

    /** 写入当前震感档位（键盘页 fn + V 循环时调用）。 */
    fun set(strength: HapticStrength) {
        _strength.value = strength
    }
}
