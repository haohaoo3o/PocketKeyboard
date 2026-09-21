package com.pocketkeyboard.app.trackpad

import com.pocketkeyboard.app.R
import com.pocketkeyboard.app.hid.HidUsage
import com.pocketkeyboard.app.keyboard.KeyAction
import com.pocketkeyboard.app.keyboard.KeyRow
import com.pocketkeyboard.app.keyboard.KeySpec
import com.pocketkeyboard.app.keyboard.KeyStyle
import com.pocketkeyboard.app.keyboard.RowItem

/**
 * 数字小键盘（Numpad）键位数据模型。
 *
 * 目录：app/src/main/java/com/pocketkeyboard/app/trackpad/
 *
 * ## 为什么复用键盘页的 [KeyRow] / [RowItem] / [KeySpec]
 *
 * 小键盘要和键盘页「风格完全一致」（黑底白字、无缝、按压下陷 + 震感），
 * 最强的保证就是**直接用同一个键帽组件和同一份键位模型**：本对象只负责
 * 「小键盘有哪些键、按下去发什么 usage」，渲染交给 `KeyCap`（见 NumpadScreen.kt），
 * 于是两页的键帽视觉、按压动画、无障碍描述天然一致。
 *
 * ## 布局结构
 *
 * 4 列 × 6 行（需求要求 4 列 5 行以上），每行 4u，最后一行是通栏回车：
 *
 * ```
 * 行 1   7   8   9   ÷
 * 行 2   4   5   6   ×
 * 行 3   1   2   3   −
 * 行 4   0  00   .   +
 * 行 5   C   %   =   ⌫
 * 行 6   return（通栏 4u）
 * ```
 *
 * ## 发送内容
 *
 * 全部走 Keyboard / Keypad Page (0x07) 的 **keypad 用法**（0x54–0x67、0xB0、0xC4），
 * 而不是主键区用法：这样被控设备（macOS / Windows / Linux）会把它当成数字小键盘输入，
 * NumLock 状态、计算器快捷键、Excel 里的数字行为都与真小键盘一致。
 * 例外：「C（清空）」没有对应的小键盘用法，映射为 `Esc`（macOS / Windows 计算器的
 * 「清除当前项」都是 Esc）；「⌫」用主键区 Backspace（0x2A），真小键盘上也是这颗。
 */
object NumpadLayout {

    /** 列数（渲染时按 4 列均分）。 */
    const val COLUMNS: Int = 4

    /** 6 行键位（行 1 → 行 6）。 */
    fun rows(): List<KeyRow> = listOf(
        // 行 1：7 8 9 ÷
        KeyRow(
            listOf(
                digit(7, R.string.key_7),
                digit(8, R.string.key_8),
                digit(9, R.string.key_9),
                operator(HidUsage.KEY_KP_DIVIDE, R.string.key_kp_divide),
            ),
        ),
        // 行 2：4 5 6 ×
        KeyRow(
            listOf(
                digit(4, R.string.key_4),
                digit(5, R.string.key_5),
                digit(6, R.string.key_6),
                operator(HidUsage.KEY_KP_MULTIPLY, R.string.key_kp_multiply),
            ),
        ),
        // 行 3：1 2 3 −
        KeyRow(
            listOf(
                digit(1, R.string.key_1),
                digit(2, R.string.key_2),
                digit(3, R.string.key_3),
                operator(HidUsage.KEY_KP_SUBTRACT, R.string.key_kp_subtract),
            ),
        ),
        // 行 4：0 00 . +
        KeyRow(
            listOf(
                digit(0, R.string.key_0),
                RowItem.Key(
                    KeySpec(
                        labelRes = R.string.key_kp_00,
                        action = KeyAction.Usage(HidUsage.KEY_KP_00),
                    ),
                ),
                operator(HidUsage.KEY_KP_DECIMAL, R.string.key_kp_decimal),
                operator(HidUsage.KEY_KP_ADD, R.string.key_kp_add),
            ),
        ),
        // 行 5：C（清空） % = ⌫（退格）
        KeyRow(
            listOf(
                RowItem.Key(
                    KeySpec(
                        labelRes = R.string.key_kp_clear,
                        action = KeyAction.Usage(HidUsage.KEY_ESCAPE),
                        contentDescRes = R.string.cd_kp_clear,
                    ),
                ),
                operator(HidUsage.KEY_KP_PERCENT, R.string.key_kp_percent),
                operator(HidUsage.KEY_KP_EQUAL, R.string.key_kp_equal),
                RowItem.Key(
                    KeySpec(
                        labelRes = R.string.key_kp_backspace,
                        action = KeyAction.Usage(HidUsage.KEY_BACKSPACE),
                        style = KeyStyle.MODIFIER,
                        contentDescRes = R.string.cd_kp_backspace,
                    ),
                ),
            ),
        ),
        // 行 6：回车（通栏 4u，方便单手确认输入）
        KeyRow(
            listOf(
                RowItem.Key(
                    KeySpec(
                        labelRes = R.string.key_enter,
                        widthU = COLUMNS.toFloat(),
                        action = KeyAction.Usage(HidUsage.KEY_KP_ENTER),
                        style = KeyStyle.MODIFIER,
                        contentDescRes = R.string.cd_kp_enter,
                    ),
                ),
            ),
        ),
    )

    /** 数字键 0–9（keypad 用法 0x62 = KP 0，0x59–0x61 = KP 1–9）。 */
    private fun digit(value: Int, labelRes: Int): RowItem.Key = RowItem.Key(
        KeySpec(
            labelRes = labelRes,
            action = KeyAction.Usage(keypadDigitUsage(value)),
        ),
    )

    /** 运算符键（+ − × ÷ = % .）。 */
    private fun operator(usage: Int, labelRes: Int): RowItem.Key = RowItem.Key(
        KeySpec(
            labelRes = labelRes,
            action = KeyAction.Usage(usage),
        ),
    )

    /**
     * 数字 → keypad usage。
     *
     * Keyboard/Keypad Page (0x07) 的小键盘区是连续的：0x59 = KP 1 … 0x61 = KP 9、
     * 0x62 = KP 0，因此 0 单独处理，1–9 用偏移量算。
     */
    private fun keypadDigitUsage(value: Int): Int = when (value) {
        0 -> HidUsage.KEY_KP_0
        else -> HidUsage.KEY_KP_1 + (value - 1)
    }
}
