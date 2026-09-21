package com.pocketkeyboard.app.keyboard

import com.pocketkeyboard.app.hid.HidModifier

/**
 * 竖屏融合页底部「可锁定修饰键排」的状态模型（纯 Kotlin，无 Compose / Android 依赖，
 * 可直接 JVM 单测）。
 *
 * 目录：app/src/main/java/com/pocketkeyboard/app/keyboard/
 *
 * ## 交互语义
 *
 * - ctrl / shift / alt(option) / win(⌘) / fn：**点击进入锁定态、再点解锁**，
 *   锁定期间每次经系统输入法提交的字符都带上对应修饰键位图
 *   （如锁定 ctrl 后打 c = Ctrl+C；锁定 shift 后打 a = A）；
 * - fn 的锁定视觉与其它修饰键一致，但**组合语义沿用横屏 87 键键盘页**
 *   （`KeyboardScreen` 的 fn 逻辑）：锁定的 fn 被第一个 fn 组合
 *   （fn+空格 背光 / fn+C 键帽颜色 / fn+S 字号 / fn+V 震感）消费后即解锁，
 *   与「粘滞的 fn 只生效一次组合」的既有约定保持一致；
 * - tab / esc 不锁定：点击直接发送 usage（[FusedModifierSpec.action]）。
 *
 * ## 为什么用不可变集合而不是可变单例
 *
 *  Compose 侧直接 `mutableStateOf(locked)` 持有本对象返回的不可变集合：
 *  点击 → 纯函数算出新集合 → 写回状态 → 重组修饰键排。状态机的每一条规则
 *  （切换、位图、fn 消费、整体复位）都能在 JVM 单测里逐条验证，不依赖
 *  Robolectric。
 */
internal object FusedModifierLatch {

    /** 点击 [key]：返回切换后的锁定集合（已锁定则移除，未锁定则加入）。 */
    fun toggle(locked: Set<LockableModifier>, key: LockableModifier): Set<LockableModifier> =
        if (key in locked) locked - key else locked + key

    /** 锁定的非 fn 修饰键 → HID 修饰键位图（fn 是被控设备之外的键，不进位图）。 */
    fun modifierBits(locked: Set<LockableModifier>): Int {
        var bits = HidModifier.NONE
        if (LockableModifier.CTRL in locked) bits = bits or HidModifier.LEFT_CTRL
        if (LockableModifier.SHIFT in locked) bits = bits or HidModifier.LEFT_SHIFT
        if (LockableModifier.ALT in locked) bits = bits or HidModifier.LEFT_ALT
        if (LockableModifier.GUI in locked) bits = bits or HidModifier.LEFT_GUI
        return bits
    }

    /** fn 是否处于锁定态。 */
    fun fnActive(locked: Set<LockableModifier>): Boolean = LockableModifier.FN in locked

    /** fn 组合已消费：清除 fn 锁定（单次组合语义）。 */
    fun withoutFn(locked: Set<LockableModifier>): Set<LockableModifier> =
        if (fnActive(locked)) locked - LockableModifier.FN else locked
}

/** 修饰键排上可锁定的修饰键（fn 含在内：锁定视觉一致，组合语义见 [FusedModifierLatch]）。 */
internal enum class LockableModifier {
    CTRL,
    SHIFT,
    ALT,
    GUI,
    FN,
}
