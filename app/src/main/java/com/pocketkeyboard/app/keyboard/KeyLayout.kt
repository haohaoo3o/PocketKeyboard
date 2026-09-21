package com.pocketkeyboard.app.keyboard

import com.pocketkeyboard.app.R
import com.pocketkeyboard.app.hid.HidUsage
import com.pocketkeyboard.app.ui.DevicePlatform

/**
 * 87 键 TKL 键盘页的键位数据模型（纯 Kotlin，无 Compose / Android 运行时依赖，
 * 键帽文案一律用 `R.string.*` 资源 ID 引用，便于单元测试与「文案全部进 strings.xml」的约束）。
 *
 * 目录：app/src/main/java/com/pocketkeyboard/app/keyboard/
 *
 * ## 宽度单位 u
 * 标准机械键盘 1u = 一个字符键宽。本模型所有宽度都是 u 的浮点比例：
 * - 行 1（Esc + F1–F12 + 三颗系统键）：16 键 × 1u = 16u
 * - 行 2（数字行 + Backspace）：13 键 × 1u + Backspace 2u = 15u（共 14 个按键）
 * - 行 3（Tab + 字母 + 反斜杠）：Tab 1.5u + 12 × 1u + 反斜杠 1.5u = 15u
 * - 行 4（Caps + 字母 + Enter）：Caps 1.75u + 11 × 1u + Enter 2.25u = 15u
 * - 行 5（Shift + 字母 + Shift）：左 Shift 2.25u + 10 × 1u + 右 Shift 2.75u = 15u
 * - 行 6（修改键 + 空格 + 修改键 + 方向键）：苹果 fn / control / option / command +
 *   command / Globe，Windows ctrl / win / alt + alt / win / fn，共 6 颗 1.25u 修改键；
 *   空格 6.25u；方向键 ← / ↑↓叠放 / → 各 1.25u → 行 6 合计 17.5u。
 *
 * ## 关于「87 键」
 * 需求逐行枚举的结构（行 1–6）共 **80 个物理键**：标准 87 键 TKL 在右 Shift 右侧
 * 还有一组 6 键编辑簇（Insert / Home / PageUp、Delete / End / PageDown），需求未列出，
 * 因此本布局不实现它。行 6 的 6 颗 1.25u 修改键 + 6.25u 空格 + 3.75u 方向键 = 17.5u，
 * 比上面各行（15u / 16u）宽 1.5–2.5u；渲染时每行按行内 weight 归一化填满屏宽，
 * 因此底行键宽约为字母键的 1.07 倍（略宽而非错位），不破坏无缝排布。
 */

/** 键帽视觉风格：只影响字号与高亮，不改变按键语义。 */
enum class KeyStyle {
    /** 普通字符键（大字号）。 */
    NORMAL,

    /** 修饰键 / 功能键（字号略小，如 esc / tab / caps / ctrl / 方向键）。 */
    MODIFIER,

    /** fn / Globe 键：fn 活跃（按住或粘滞）时整颗键帽高亮。 */
    FN,
}

/** 键帽上绘制的白色矢量图标（媒体键 / Globe / Win 键），绘制实现见 KeyCap.kt。 */
enum class KeyIcon {
    BRIGHTNESS_DOWN,
    BRIGHTNESS_UP,
    PREVIOUS_TRACK,
    PLAY_PAUSE,
    NEXT_TRACK,
    MUTE,
    VOLUME_DOWN,
    VOLUME_UP,
    STOP,
    EJECT,
    SLEEP,
    POWER,
    GLOBE,
    WIN,
}

/**
 * 单个按键的语义：决定按下时经 [com.pocketkeyboard.app.hid.HidTransport] 发送什么。
 *
 * 例外：[Fn] 与「fn 组合的本机功能」（亮度 / 颜色 / 字号 / 震感）不发送给被控设备。
 */
sealed interface KeyAction {

    /** 字符键：经 `HidUsageMapper.usageForChar` 查 usage，自动判断是否需要 Shift。 */
    data class Character(val char: Char) : KeyAction

    /** 固定 Keyboard Page (0x07) usage：Esc / Tab / Caps / 方向键 / PrtSc 等。 */
    data class Usage(val usage: Int) : KeyAction

    /** 修饰键：按住期间置位、松开清位（usage 见 `HidUsage.KEY_LEFT_GUI` 等）。 */
    data class Modifier(val usage: Int) : KeyAction

    /**
     * F 行双行为键：默认行为发 Consumer Page 媒体键（[consumerUsage]），
     * fn 活跃时改发 Keyboard Page 本义（[nativeUsage]，即 F1–F12）。
     */
    data class FunctionKey(val nativeUsage: Int, val consumerUsage: Int) : KeyAction

    /** 本机 fn / Globe 键：不发送给被控设备，只切换 fn 粘滞状态。 */
    data object Fn : KeyAction
}

/**
 * 单个按键的完整描述。
 *
 * @param labelRes 键帽主文案资源 ID（字母 / 数字 / 符号 / esc / tab 等）
 * @param widthU 键宽，单位 u（1u = 一个字符键宽）
 * @param action 按键语义
 * @param style 键帽视觉风格
 * @param subLabelRes 副文案资源 ID：Shift 上档字符（小字叠在字母上方），
 *                    或苹果布局 F 行媒体图标下方的小字 f1–f12
 * @param icon 矢量图标（与 labelRes 并存：F 行 fn 活跃时改显示 labelRes 的 F1–F12）
 * @param contentDescRes 图标键的无障碍描述
 */
data class KeySpec(
    val labelRes: Int,
    val widthU: Float = 1f,
    val action: KeyAction,
    val style: KeyStyle = KeyStyle.NORMAL,
    val subLabelRes: Int? = null,
    val icon: KeyIcon? = null,
    val contentDescRes: Int? = null,
)

/** 一行里的一个格子：普通单键，或上下叠放的键槽（方向键区的 ↑/↓）。 */
sealed interface RowItem {

    /** 该格子占用的行宽（u）。 */
    val widthU: Float

    /** 普通单键。 */
    data class Key(val spec: KeySpec) : RowItem {
        override val widthU: Float get() = spec.widthU
    }

    /** 上下叠放键槽：多个按键共用同一列宽，垂直排列（方向键区的 ↑ / ↓）。 */
    data class Stacked(
        val keys: List<KeySpec>,
        override val widthU: Float,
    ) : RowItem
}

/** 一行按键。[items] 按从左到右排列，[totalU] 为该行 u 总数。 */
data class KeyRow(val items: List<RowItem>) {

    /** 该行 u 总数（行 1–5 为 15u 或 16u，行 6 为 16u）。 */
    val totalU: Float
        get() = items.sumOf { it.widthU.toDouble() }.toFloat()

    /** 该行物理按键数（叠放键槽按 2 个计）。 */
    val keyCount: Int
        get() = items.sumOf { item ->
            when (item) {
                is RowItem.Key -> 1
                is RowItem.Stacked -> item.keys.size
            }
        }
}

/**
 * 标准 87 键 TKL（无小键盘区）键位表。
 *
 * 布局随 [DevicePlatform] 切换，差异只有两处：
 * 1. F 行：苹果布局在媒体图标下方加小字 f1–f12（`subLabelRes`），Windows 布局只显示图标；
 * 2. 行 6 修改键：苹果为 fn / control / option / command + command / Globe，
 *    Windows 为 ctrl / win / alt + alt / win / fn。
 *
 * 两套布局的 F 行**默认行为都是媒体快捷键**；fn 活跃时才发送 F1–F12 本义
 * （见 [KeyAction.FunctionKey] 与 KeyboardScreen 的 fn 组合处理）。
 */
object TklLayout {

    /** 标准 1u。 */
    const val U: Float = 1f

    /** 修饰键 / 方向键宽（标准 1.25u）。 */
    private const val U_MOD: Float = 1.25f

    /** 空格宽（标准 6.25u）。 */
    private const val U_SPACE: Float = 6.25f

    /** Backspace 2u / Enter 2.25u / Caps 1.75u / Shift 2.25u / 右 Shift 2.75u / Tab 1.5u。 */
    private const val U_BACKSPACE: Float = 2f
    private const val U_ENTER: Float = 2.25f
    private const val U_CAPS: Float = 1.75f
    private const val U_SHIFT_L: Float = 2.25f
    private const val U_SHIFT_R: Float = 2.75f
    private const val U_TAB: Float = 1.5f

    /** 6 行键位（行 1 → 行 6）。 */
    fun rows(platform: DevicePlatform): List<KeyRow> = listOf(
        functionRow(platform),
        numberRow(),
        topLetterRow(),
        homeRow(),
        bottomLetterRow(),
        modifierRow(platform),
    )

    // ------------------------------------------------------------------ 行 1：Esc + F1–F12 + 系统键

    private fun functionRow(platform: DevicePlatform): KeyRow = KeyRow(
        buildList {
            add(
                RowItem.Key(
                    KeySpec(
                        labelRes = R.string.key_esc,
                        action = KeyAction.Usage(HidUsage.KEY_ESCAPE),
                        style = KeyStyle.MODIFIER,
                        contentDescRes = R.string.key_esc,
                    ),
                ),
            )
            // F1–F12：默认发媒体键，fn 活跃时发 F1–F12 本义
            for (index in 0 until F_ROW_CONSUMER_USAGE.size) {
                add(
                    RowItem.Key(
                        KeySpec(
                            labelRes = F_LABEL_RES[index],
                            action = KeyAction.FunctionKey(
                                nativeUsage = HidUsage.KEY_F1 + index,
                                consumerUsage = F_ROW_CONSUMER_USAGE[index],
                            ),
                            style = KeyStyle.MODIFIER,
                            // 苹果布局在媒体图标下方叠小字 f1–f12；Windows 布局不叠
                            subLabelRes = if (platform == DevicePlatform.APPLE) {
                                F_SMALL_LABEL_RES[index]
                            } else {
                                null
                            },
                            icon = F_ROW_ICON[index],
                            contentDescRes = F_ROW_CONTENT_DESC_RES[index],
                        ),
                    ),
                )
            }
            add(
                RowItem.Key(
                    KeySpec(
                        labelRes = R.string.key_prtsc,
                        action = KeyAction.Usage(HidUsage.KEY_PRINT_SCREEN),
                        style = KeyStyle.MODIFIER,
                        contentDescRes = R.string.key_prtsc,
                    ),
                ),
            )
            add(
                RowItem.Key(
                    KeySpec(
                        labelRes = R.string.key_scrlk,
                        action = KeyAction.Usage(HidUsage.KEY_SCROLL_LOCK),
                        style = KeyStyle.MODIFIER,
                        contentDescRes = R.string.key_scrlk,
                    ),
                ),
            )
            add(
                RowItem.Key(
                    KeySpec(
                        labelRes = R.string.key_pause,
                        action = KeyAction.Usage(HidUsage.KEY_PAUSE),
                        style = KeyStyle.MODIFIER,
                        contentDescRes = R.string.key_pause,
                    ),
                ),
            )
        },
    )

    // ------------------------------------------------------------------ 行 2：数字 1–0 与标点 + Backspace(2u)

    private fun numberRow(): KeyRow = KeyRow(
        buildList {
            add(
                RowItem.Key(
                    KeySpec(
                        labelRes = R.string.key_grave,
                        action = KeyAction.Character('`'),
                        subLabelRes = R.string.key_tilde,
                    ),
                ),
            )
            for (index in DIGIT_CHARS.indices) {
                add(
                    RowItem.Key(
                        KeySpec(
                            labelRes = DIGIT_LABEL_RES[index],
                            action = KeyAction.Character(DIGIT_CHARS[index]),
                            subLabelRes = DIGIT_SHIFT_LABEL_RES[index],
                        ),
                    ),
                )
            }
            add(
                RowItem.Key(
                    KeySpec(
                        labelRes = R.string.key_minus,
                        action = KeyAction.Character('-'),
                        subLabelRes = R.string.key_underscore,
                    ),
                ),
            )
            add(
                RowItem.Key(
                    KeySpec(
                        labelRes = R.string.key_equal,
                        action = KeyAction.Character('='),
                        subLabelRes = R.string.key_plus,
                    ),
                ),
            )
            add(
                RowItem.Key(
                    KeySpec(
                        labelRes = R.string.key_backspace,
                        widthU = U_BACKSPACE,
                        action = KeyAction.Usage(HidUsage.KEY_BACKSPACE),
                        style = KeyStyle.MODIFIER,
                        contentDescRes = R.string.key_backspace,
                    ),
                ),
            )
        },
    )

    // ------------------------------------------------------------------ 行 3：Tab(1.5u) + 字母 + 反斜杠(1.5u)

    private fun topLetterRow(): KeyRow = KeyRow(
        buildList {
            add(
                RowItem.Key(
                    KeySpec(
                        labelRes = R.string.key_tab,
                        widthU = U_TAB,
                        action = KeyAction.Usage(HidUsage.KEY_TAB),
                        style = KeyStyle.MODIFIER,
                        contentDescRes = R.string.key_tab,
                    ),
                ),
            )
            for (index in TOP_LETTER_CHARS.indices) {
                add(
                    RowItem.Key(
                        KeySpec(
                            labelRes = TOP_LETTER_LABEL_RES[index],
                            action = KeyAction.Character(TOP_LETTER_CHARS[index]),
                        ),
                    ),
                )
            }
            add(
                RowItem.Key(
                    KeySpec(
                        labelRes = R.string.key_lbracket,
                        action = KeyAction.Character('['),
                        subLabelRes = R.string.key_lbrace,
                    ),
                ),
            )
            add(
                RowItem.Key(
                    KeySpec(
                        labelRes = R.string.key_rbracket,
                        action = KeyAction.Character(']'),
                        subLabelRes = R.string.key_rbrace,
                    ),
                ),
            )
            add(
                RowItem.Key(
                    KeySpec(
                        labelRes = R.string.key_backslash,
                        widthU = U_TAB,
                        action = KeyAction.Character('\\'),
                        subLabelRes = R.string.key_pipe,
                    ),
                ),
            )
        },
    )

    // ------------------------------------------------------------------ 行 4：Caps(1.75u) + 字母 + Enter(2.25u)

    private fun homeRow(): KeyRow = KeyRow(
        buildList {
            add(
                RowItem.Key(
                    KeySpec(
                        labelRes = R.string.key_caps,
                        widthU = U_CAPS,
                        action = KeyAction.Usage(HidUsage.KEY_CAPS_LOCK),
                        style = KeyStyle.MODIFIER,
                        contentDescRes = R.string.key_caps,
                    ),
                ),
            )
            for (index in HOME_LETTER_CHARS.indices) {
                add(
                    RowItem.Key(
                        KeySpec(
                            labelRes = HOME_LETTER_LABEL_RES[index],
                            action = KeyAction.Character(HOME_LETTER_CHARS[index]),
                        ),
                    ),
                )
            }
            add(
                RowItem.Key(
                    KeySpec(
                        labelRes = R.string.key_semicolon,
                        action = KeyAction.Character(';'),
                        subLabelRes = R.string.key_colon,
                    ),
                ),
            )
            add(
                RowItem.Key(
                    KeySpec(
                        labelRes = R.string.key_apostrophe,
                        action = KeyAction.Character('\''),
                        subLabelRes = R.string.key_quote,
                    ),
                ),
            )
            add(
                RowItem.Key(
                    KeySpec(
                        labelRes = R.string.key_enter,
                        widthU = U_ENTER,
                        action = KeyAction.Usage(HidUsage.KEY_ENTER),
                        style = KeyStyle.MODIFIER,
                        contentDescRes = R.string.key_enter,
                    ),
                ),
            )
        },
    )

    // ------------------------------------------------------------------ 行 5：左 Shift(2.25u) + 字母 + 右 Shift(2.75u)

    private fun bottomLetterRow(): KeyRow = KeyRow(
        buildList {
            add(
                RowItem.Key(
                    KeySpec(
                        labelRes = R.string.key_shift_l,
                        widthU = U_SHIFT_L,
                        action = KeyAction.Modifier(HidUsage.KEY_LEFT_SHIFT),
                        style = KeyStyle.MODIFIER,
                        contentDescRes = R.string.key_shift_l,
                    ),
                ),
            )
            for (index in BOTTOM_LETTER_CHARS.indices) {
                add(
                    RowItem.Key(
                        KeySpec(
                            labelRes = BOTTOM_LETTER_LABEL_RES[index],
                            action = KeyAction.Character(BOTTOM_LETTER_CHARS[index]),
                        ),
                    ),
                )
            }
            add(
                RowItem.Key(
                    KeySpec(
                        labelRes = R.string.key_comma,
                        action = KeyAction.Character(','),
                        subLabelRes = R.string.key_lt,
                    ),
                ),
            )
            add(
                RowItem.Key(
                    KeySpec(
                        labelRes = R.string.key_period,
                        action = KeyAction.Character('.'),
                        subLabelRes = R.string.key_gt,
                    ),
                ),
            )
            add(
                RowItem.Key(
                    KeySpec(
                        labelRes = R.string.key_slash,
                        action = KeyAction.Character('/'),
                        subLabelRes = R.string.key_question,
                    ),
                ),
            )
            add(
                RowItem.Key(
                    KeySpec(
                        labelRes = R.string.key_shift_r,
                        widthU = U_SHIFT_R,
                        action = KeyAction.Modifier(HidUsage.KEY_RIGHT_SHIFT),
                        style = KeyStyle.MODIFIER,
                        contentDescRes = R.string.key_shift_r,
                    ),
                ),
            )
        },
    )

    // ------------------------------------------------------------------ 行 6：修改键 + 空格(6.25u) + 修改键 + 方向键（上下叠放）

    private fun modifierRow(platform: DevicePlatform): KeyRow = KeyRow(
        buildList {
            when (platform) {
                // 苹果：fn / control / option / command ｜ 空格 ｜ command / Globe
                DevicePlatform.APPLE -> {
                    add(fnKey(R.string.key_fn))
                    add(modifierKey(R.string.key_control_l, HidUsage.KEY_LEFT_CONTROL, R.string.key_control_l))
                    add(modifierKey(R.string.key_option_l, HidUsage.KEY_LEFT_ALT, R.string.key_option_l))
                    add(modifierKey(R.string.key_cmd_l, HidUsage.KEY_LEFT_GUI, R.string.key_cmd_l))
                    add(spaceKey())
                    add(modifierKey(R.string.key_cmd_r, HidUsage.KEY_RIGHT_GUI, R.string.key_cmd_r))
                    add(globeKey())
                }
                // Windows：ctrl / win / alt ｜ 空格 ｜ alt / win / fn
                DevicePlatform.OTHER -> {
                    add(modifierKey(R.string.key_ctrl_l, HidUsage.KEY_LEFT_CONTROL, R.string.key_ctrl_l))
                    add(winKey(HidUsage.KEY_LEFT_GUI))
                    add(modifierKey(R.string.key_alt_l, HidUsage.KEY_LEFT_ALT, R.string.key_alt_l))
                    add(spaceKey())
                    add(modifierKey(R.string.key_alt_r, HidUsage.KEY_RIGHT_ALT, R.string.key_alt_r))
                    add(winKey(HidUsage.KEY_RIGHT_GUI))
                    add(fnKey(R.string.key_fn))
                }
            }
            // 方向键区：← / ↑↓叠放 / →
            addAll(arrowCluster())
        },
    )

    private fun fnKey(labelRes: Int): RowItem.Key = RowItem.Key(
        KeySpec(
            labelRes = labelRes,
            widthU = U_MOD,
            action = KeyAction.Fn,
            style = KeyStyle.FN,
            contentDescRes = R.string.key_fn,
        ),
    )

    private fun globeKey(): RowItem.Key = RowItem.Key(
        KeySpec(
            labelRes = R.string.key_globe,
            widthU = U_MOD,
            action = KeyAction.Fn,
            style = KeyStyle.FN,
            icon = KeyIcon.GLOBE,
            contentDescRes = R.string.cd_globe,
        ),
    )

    private fun winKey(guiUsage: Int): RowItem.Key = RowItem.Key(
        KeySpec(
            labelRes = R.string.key_win,
            widthU = U_MOD,
            action = KeyAction.Modifier(guiUsage),
            style = KeyStyle.MODIFIER,
            icon = KeyIcon.WIN,
            contentDescRes = R.string.cd_win,
        ),
    )

    private fun modifierKey(
        labelRes: Int,
        usage: Int,
        contentDescRes: Int,
    ): RowItem.Key = RowItem.Key(
        KeySpec(
            labelRes = labelRes,
            widthU = U_MOD,
            action = KeyAction.Modifier(usage),
            style = KeyStyle.MODIFIER,
            contentDescRes = contentDescRes,
        ),
    )

    private fun spaceKey(): RowItem.Key = RowItem.Key(
        KeySpec(
            labelRes = R.string.key_space,
            widthU = U_SPACE,
            action = KeyAction.Usage(HidUsage.KEY_SPACE),
            style = KeyStyle.MODIFIER,
            contentDescRes = R.string.key_space,
        ),
    )

    private fun arrowCluster(): List<RowItem> = listOf(
        RowItem.Key(
            KeySpec(
                labelRes = R.string.key_arrow_left,
                widthU = U_MOD,
                action = KeyAction.Usage(HidUsage.KEY_LEFT_ARROW),
                style = KeyStyle.MODIFIER,
                contentDescRes = R.string.key_arrow_left,
            ),
        ),
        RowItem.Stacked(
            keys = listOf(
                KeySpec(
                    labelRes = R.string.key_arrow_up,
                    action = KeyAction.Usage(HidUsage.KEY_UP_ARROW),
                    style = KeyStyle.MODIFIER,
                    contentDescRes = R.string.key_arrow_up,
                ),
                KeySpec(
                    labelRes = R.string.key_arrow_down,
                    action = KeyAction.Usage(HidUsage.KEY_DOWN_ARROW),
                    style = KeyStyle.MODIFIER,
                    contentDescRes = R.string.key_arrow_down,
                ),
            ),
            widthU = U_MOD,
        ),
        RowItem.Key(
            KeySpec(
                labelRes = R.string.key_arrow_right,
                widthU = U_MOD,
                action = KeyAction.Usage(HidUsage.KEY_RIGHT_ARROW),
                style = KeyStyle.MODIFIER,
                contentDescRes = R.string.key_arrow_right,
            ),
        ),
    )

    // ------------------------------------------------------------------ 静态查表

    private val DIGIT_CHARS = charArrayOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '0')

    private val TOP_LETTER_CHARS = "qwertyuiop".toCharArray()
    private val HOME_LETTER_CHARS = "asdfghjkl".toCharArray()
    private val BOTTOM_LETTER_CHARS = "zxcvbnm".toCharArray()

    /** F1–F12 大字号文案（fn 活跃时显示）。 */
    private val F_LABEL_RES = intArrayOf(
        R.string.key_f1, R.string.key_f2, R.string.key_f3, R.string.key_f4,
        R.string.key_f5, R.string.key_f6, R.string.key_f7, R.string.key_f8,
        R.string.key_f9, R.string.key_f10, R.string.key_f11, R.string.key_f12,
    )

    /** F1–F12 小字号文案（苹果布局媒体图标下方）。 */
    private val F_SMALL_LABEL_RES = intArrayOf(
        R.string.key_f1_small, R.string.key_f2_small, R.string.key_f3_small, R.string.key_f4_small,
        R.string.key_f5_small, R.string.key_f6_small, R.string.key_f7_small, R.string.key_f8_small,
        R.string.key_f9_small, R.string.key_f10_small, R.string.key_f11_small, R.string.key_f12_small,
    )

    /** F 行默认行为：Consumer Page (0x0C) 媒体键 usage。 */
    private val F_ROW_CONSUMER_USAGE = intArrayOf(
        HidUsage.CONSUMER_BRIGHTNESS_DOWN,       // F1 亮度-
        HidUsage.CONSUMER_BRIGHTNESS_UP,         // F2 亮度+
        HidUsage.CONSUMER_SCAN_PREVIOUS_TRACK,   // F3 上一首
        HidUsage.CONSUMER_PLAY_PAUSE,            // F4 播放 / 暂停
        HidUsage.CONSUMER_SCAN_NEXT_TRACK,       // F5 下一首
        HidUsage.CONSUMER_MUTE,                  // F6 静音
        HidUsage.CONSUMER_VOLUME_DOWN,           // F7 音量-
        HidUsage.CONSUMER_VOLUME_UP,             // F8 音量+
        HidUsage.CONSUMER_STOP,                  // F9 停止
        HidUsage.CONSUMER_EJECT,                 // F10 弹出
        HidUsage.CONSUMER_SLEEP,                 // F11 睡眠
        HidUsage.CONSUMER_POWER,                 // F12 电源
    )

    /** F 行媒体图标。 */
    private val F_ROW_ICON = listOf(
        KeyIcon.BRIGHTNESS_DOWN,
        KeyIcon.BRIGHTNESS_UP,
        KeyIcon.PREVIOUS_TRACK,
        KeyIcon.PLAY_PAUSE,
        KeyIcon.NEXT_TRACK,
        KeyIcon.MUTE,
        KeyIcon.VOLUME_DOWN,
        KeyIcon.VOLUME_UP,
        KeyIcon.STOP,
        KeyIcon.EJECT,
        KeyIcon.SLEEP,
        KeyIcon.POWER,
    )

    /** F 行媒体图标无障碍描述。 */
    private val F_ROW_CONTENT_DESC_RES = intArrayOf(
        R.string.cd_brightness_down,
        R.string.cd_brightness_up,
        R.string.cd_previous_track,
        R.string.cd_play_pause,
        R.string.cd_next_track,
        R.string.cd_mute,
        R.string.cd_volume_down,
        R.string.cd_volume_up,
        R.string.cd_stop,
        R.string.cd_eject,
        R.string.cd_sleep,
        R.string.cd_power,
    )

    private val DIGIT_LABEL_RES = intArrayOf(
        R.string.key_1, R.string.key_2, R.string.key_3, R.string.key_4, R.string.key_5,
        R.string.key_6, R.string.key_7, R.string.key_8, R.string.key_9, R.string.key_0,
    )

    private val DIGIT_SHIFT_LABEL_RES = intArrayOf(
        R.string.key_exclam, R.string.key_at, R.string.key_hash, R.string.key_dollar, R.string.key_percent,
        R.string.key_caret, R.string.key_ampersand, R.string.key_asterisk, R.string.key_lparen, R.string.key_rparen,
    )

    private val TOP_LETTER_LABEL_RES = intArrayOf(
        R.string.key_q, R.string.key_w, R.string.key_e, R.string.key_r, R.string.key_t,
        R.string.key_y, R.string.key_u, R.string.key_i, R.string.key_o, R.string.key_p,
    )

    private val HOME_LETTER_LABEL_RES = intArrayOf(
        R.string.key_a, R.string.key_s, R.string.key_d, R.string.key_f, R.string.key_g,
        R.string.key_h, R.string.key_j, R.string.key_k, R.string.key_l,
    )

    private val BOTTOM_LETTER_LABEL_RES = intArrayOf(
        R.string.key_z, R.string.key_x, R.string.key_c, R.string.key_v, R.string.key_b,
        R.string.key_n, R.string.key_m,
    )
}
