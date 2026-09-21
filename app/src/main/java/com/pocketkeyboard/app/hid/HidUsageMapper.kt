package com.pocketkeyboard.app.hid

import android.view.KeyEvent

/**
 * Android keyCode / 字符 → HID Usage ID 映射表（纯函数，无 Android 运行时调用）。
 *
 * 覆盖范围（87 键 TKL 全部按键 + 小键盘 + 常用 Consumer 媒体键）：
 * - 字母 a–z、数字 0–9、全部符号键
 * - Esc、F1–F12（另含 F13–F24）、PrintScreen、Scroll Lock、Pause/Break
 * - Tab、Caps Lock、Shift / Ctrl / Alt / GUI(⌘/Win) 左右共 8 个修饰键
 * - Backspace、Delete Forward、Insert、Home、End、Page Up/Down
 * - 四个方向键、Enter、Space、Application(菜单键)
 * - 小键盘 0–9 / . , + - * / = Enter 与「00」「000」「%」「(」「)」「+/-」
 * - Consumer Page 媒体键：音量± / 静音 / 播放暂停 / 上下曲 / 停止 / 亮度
 *
 * ## 为什么可以直接被 JVM 单元测试引用
 * 本文件只读取 `android.view.KeyEvent` 的 `public static final int` 常量，Kotlin 编译器会把
 * Java 编译期常量直接内联进字节码，因此单元测试运行期不会加载任何 Android 框架类；
 * [usageForChar] 等纯字符路径则完全不依赖 Android。
 */
object HidUsageMapper {

    /** 字符映射结果：[usage] 为 HID Usage ID，[needsShift] 表示是否需要同时按 Shift。 */
    data class CharMapping(val usage: Int, val needsShift: Boolean)

    // ------------------------------------------------------------------ Android keyCode → usage

    /**
     * Android keyCode → Keyboard/Keypad Page (0x07) Usage ID。
     *
     * 没有对应 HID usage 的键（例如 Android 的返回 / 主页 / 多任务等系统导航键）**故意不映射**：
     * 这些键在被控设备上应由系统手势处理，不应作为普通按键上报。
     */
    val KEYCODE_TO_USAGE: Map<Int, Int> = buildMap {
        // ---- 字母 A–Z（KeyEvent.KEYCODE_A = 29 … KEYCODE_Z = 54）----
        put(KeyEvent.KEYCODE_A, HidUsage.KEY_A)
        put(KeyEvent.KEYCODE_B, 0x05)
        put(KeyEvent.KEYCODE_C, 0x06)
        put(KeyEvent.KEYCODE_D, 0x07)
        put(KeyEvent.KEYCODE_E, 0x08)
        put(KeyEvent.KEYCODE_F, 0x09)
        put(KeyEvent.KEYCODE_G, 0x0A)
        put(KeyEvent.KEYCODE_H, 0x0B)
        put(KeyEvent.KEYCODE_I, 0x0C)
        put(KeyEvent.KEYCODE_J, 0x0D)
        put(KeyEvent.KEYCODE_K, 0x0E)
        put(KeyEvent.KEYCODE_L, 0x0F)
        put(KeyEvent.KEYCODE_M, 0x10)
        put(KeyEvent.KEYCODE_N, 0x11)
        put(KeyEvent.KEYCODE_O, 0x12)
        put(KeyEvent.KEYCODE_P, 0x13)
        put(KeyEvent.KEYCODE_Q, 0x14)
        put(KeyEvent.KEYCODE_R, 0x15)
        put(KeyEvent.KEYCODE_S, 0x16)
        put(KeyEvent.KEYCODE_T, 0x17)
        put(KeyEvent.KEYCODE_U, 0x18)
        put(KeyEvent.KEYCODE_V, 0x19)
        put(KeyEvent.KEYCODE_W, 0x1A)
        put(KeyEvent.KEYCODE_X, 0x1B)
        put(KeyEvent.KEYCODE_Y, 0x1C)
        put(KeyEvent.KEYCODE_Z, HidUsage.KEY_Z)

        // ---- 数字行 ----
        put(KeyEvent.KEYCODE_1, HidUsage.KEY_1)
        put(KeyEvent.KEYCODE_2, 0x1F)
        put(KeyEvent.KEYCODE_3, 0x20)
        put(KeyEvent.KEYCODE_4, 0x21)
        put(KeyEvent.KEYCODE_5, 0x22)
        put(KeyEvent.KEYCODE_6, 0x23)
        put(KeyEvent.KEYCODE_7, 0x24)
        put(KeyEvent.KEYCODE_8, 0x25)
        put(KeyEvent.KEYCODE_9, 0x26)
        put(KeyEvent.KEYCODE_0, HidUsage.KEY_0)

        // ---- 符号键 ----
        put(KeyEvent.KEYCODE_GRAVE, HidUsage.KEY_GRAVE)
        put(KeyEvent.KEYCODE_MINUS, HidUsage.KEY_MINUS)
        put(KeyEvent.KEYCODE_EQUALS, HidUsage.KEY_EQUAL)
        put(KeyEvent.KEYCODE_LEFT_BRACKET, HidUsage.KEY_LEFT_BRACKET)
        put(KeyEvent.KEYCODE_RIGHT_BRACKET, HidUsage.KEY_RIGHT_BRACKET)
        put(KeyEvent.KEYCODE_BACKSLASH, HidUsage.KEY_BACKSLASH)
        put(KeyEvent.KEYCODE_SEMICOLON, HidUsage.KEY_SEMICOLON)
        put(KeyEvent.KEYCODE_APOSTROPHE, HidUsage.KEY_APOSTROPHE)
        put(KeyEvent.KEYCODE_COMMA, HidUsage.KEY_COMMA)
        put(KeyEvent.KEYCODE_PERIOD, HidUsage.KEY_PERIOD)
        put(KeyEvent.KEYCODE_SLASH, HidUsage.KEY_SLASH)

        // ---- 功能键 ----
        put(KeyEvent.KEYCODE_ESCAPE, HidUsage.KEY_ESCAPE)
        put(KeyEvent.KEYCODE_F1, HidUsage.KEY_F1)
        put(KeyEvent.KEYCODE_F2, 0x3B)
        put(KeyEvent.KEYCODE_F3, 0x3C)
        put(KeyEvent.KEYCODE_F4, 0x3D)
        put(KeyEvent.KEYCODE_F5, 0x3E)
        put(KeyEvent.KEYCODE_F6, 0x3F)
        put(KeyEvent.KEYCODE_F7, 0x40)
        put(KeyEvent.KEYCODE_F8, 0x41)
        put(KeyEvent.KEYCODE_F9, 0x42)
        put(KeyEvent.KEYCODE_F10, 0x43)
        put(KeyEvent.KEYCODE_F11, 0x44)
        put(KeyEvent.KEYCODE_F12, HidUsage.KEY_F12)

        // ---- 编辑 / 导航区 ----
        put(KeyEvent.KEYCODE_TAB, HidUsage.KEY_TAB)
        put(KeyEvent.KEYCODE_SPACE, HidUsage.KEY_SPACE)
        put(KeyEvent.KEYCODE_ENTER, HidUsage.KEY_ENTER)
        put(KeyEvent.KEYCODE_DEL, HidUsage.KEY_BACKSPACE)
        put(KeyEvent.KEYCODE_FORWARD_DEL, HidUsage.KEY_DELETE_FORWARD)
        put(KeyEvent.KEYCODE_INSERT, HidUsage.KEY_INSERT)
        put(KeyEvent.KEYCODE_MOVE_HOME, HidUsage.KEY_HOME)
        put(KeyEvent.KEYCODE_MOVE_END, HidUsage.KEY_END)
        put(KeyEvent.KEYCODE_PAGE_UP, HidUsage.KEY_PAGE_UP)
        put(KeyEvent.KEYCODE_PAGE_DOWN, HidUsage.KEY_PAGE_DOWN)
        put(KeyEvent.KEYCODE_SYSRQ, HidUsage.KEY_PRINT_SCREEN)
        put(KeyEvent.KEYCODE_BREAK, HidUsage.KEY_PAUSE)
        put(KeyEvent.KEYCODE_SCROLL_LOCK, HidUsage.KEY_SCROLL_LOCK)
        put(KeyEvent.KEYCODE_CAPS_LOCK, HidUsage.KEY_CAPS_LOCK)
        put(KeyEvent.KEYCODE_NUM_LOCK, HidUsage.KEY_NUM_LOCK)
        put(KeyEvent.KEYCODE_MENU, HidUsage.KEY_APPLICATION)

        // ---- 方向键 ----
        put(KeyEvent.KEYCODE_DPAD_UP, HidUsage.KEY_UP_ARROW)
        put(KeyEvent.KEYCODE_DPAD_DOWN, HidUsage.KEY_DOWN_ARROW)
        put(KeyEvent.KEYCODE_DPAD_LEFT, HidUsage.KEY_LEFT_ARROW)
        put(KeyEvent.KEYCODE_DPAD_RIGHT, HidUsage.KEY_RIGHT_ARROW)

        // ---- 修饰键（详见 [modifierUsageFor]）----
        put(KeyEvent.KEYCODE_CTRL_LEFT, HidUsage.KEY_LEFT_CONTROL)
        put(KeyEvent.KEYCODE_CTRL_RIGHT, HidUsage.KEY_RIGHT_CONTROL)
        put(KeyEvent.KEYCODE_SHIFT_LEFT, HidUsage.KEY_LEFT_SHIFT)
        put(KeyEvent.KEYCODE_SHIFT_RIGHT, HidUsage.KEY_RIGHT_SHIFT)
        put(KeyEvent.KEYCODE_ALT_LEFT, HidUsage.KEY_LEFT_ALT)
        put(KeyEvent.KEYCODE_ALT_RIGHT, HidUsage.KEY_RIGHT_ALT)
        put(KeyEvent.KEYCODE_META_LEFT, HidUsage.KEY_LEFT_GUI)
        put(KeyEvent.KEYCODE_META_RIGHT, HidUsage.KEY_RIGHT_GUI)

        // ---- 小键盘（NUMPAD 模式使用）----
        put(KeyEvent.KEYCODE_NUMPAD_0, HidUsage.KEY_KP_0)
        put(KeyEvent.KEYCODE_NUMPAD_1, HidUsage.KEY_KP_1)
        put(KeyEvent.KEYCODE_NUMPAD_2, 0x5A)
        put(KeyEvent.KEYCODE_NUMPAD_3, 0x5B)
        put(KeyEvent.KEYCODE_NUMPAD_4, 0x5C)
        put(KeyEvent.KEYCODE_NUMPAD_5, 0x5D)
        put(KeyEvent.KEYCODE_NUMPAD_6, 0x5E)
        put(KeyEvent.KEYCODE_NUMPAD_7, 0x5F)
        put(KeyEvent.KEYCODE_NUMPAD_8, 0x60)
        put(KeyEvent.KEYCODE_NUMPAD_9, HidUsage.KEY_KP_9)
        put(KeyEvent.KEYCODE_NUMPAD_DOT, HidUsage.KEY_KP_DECIMAL)
        put(KeyEvent.KEYCODE_NUMPAD_COMMA, HidUsage.KEY_KP_COMMA)
        put(KeyEvent.KEYCODE_NUMPAD_DIVIDE, HidUsage.KEY_KP_DIVIDE)
        put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, HidUsage.KEY_KP_MULTIPLY)
        put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, HidUsage.KEY_KP_SUBTRACT)
        put(KeyEvent.KEYCODE_NUMPAD_ADD, HidUsage.KEY_KP_ADD)
        put(KeyEvent.KEYCODE_NUMPAD_EQUALS, HidUsage.KEY_KP_EQUAL)
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, HidUsage.KEY_KP_ENTER)
    }

    /**
     * 小键盘扩展键（标准键盘上没有、Android 也没有对应 keyCode）→ usage。
     *
     * 键盘页 / 小键盘页通过 [usageForKeyName] 查询，键名与键帽标签一致。
     */
    val NUMPAD_KEYS: Map<String, Int> = mapOf(
        "00" to HidUsage.KEY_KP_00,
        "000" to HidUsage.KEY_KP_000,
        "%" to HidUsage.KEY_KP_PERCENT,
        "(" to HidUsage.KEY_KP_LEFT_PARENTHESIS,
        ")" to HidUsage.KEY_KP_RIGHT_PARENTHESIS,
        "+/-" to HidUsage.KEY_KP_PLUS_MINUS,
        "=" to HidUsage.KEY_KP_EQUAL,
        "," to HidUsage.KEY_KP_COMMA,
    )

    /** 按键显示名查询 usage（用于「00」「+/-」等非标准键）。 */
    fun usageForKeyName(name: String): Int? = NUMPAD_KEYS[name]

    /** Android keyCode → HID usage；未映射返回 null。 */
    fun usageForKeyCode(androidKeyCode: Int): Int? = KEYCODE_TO_USAGE[androidKeyCode]

    /** 该 keyCode 是否映射为修饰键；非修饰键返回 null。 */
    fun modifierUsageFor(androidKeyCode: Int): Int? {
        val usage = KEYCODE_TO_USAGE[androidKeyCode] ?: return null
        return if (HidModifier.isModifier(usage)) usage else null
    }

    // ------------------------------------------------------------------ 字符 → usage

    /**
     * 无需 Shift 即可输入的字符 → usage。
     *
     * 大写字母**故意不在这里**：直接发 `KEY_A`（不带 Shift）在对端输入的是小写 `a`，
     * 大写必须走 [SHIFTED_CHAR_TO_USAGE]（usage + LeftShift）。
     */
    val CHAR_TO_USAGE: Map<Char, Int> = buildMap {
        ('a'..'z').forEachIndexed { index, c -> put(c, HidUsage.KEY_A + index) }
        ('1'..'9').forEachIndexed { index, c -> put(c, HidUsage.KEY_1 + index) }
        put('0', HidUsage.KEY_0)
        put(' ', HidUsage.KEY_SPACE)
        put('-', HidUsage.KEY_MINUS)
        put('=', HidUsage.KEY_EQUAL)
        put('[', HidUsage.KEY_LEFT_BRACKET)
        put(']', HidUsage.KEY_RIGHT_BRACKET)
        put('\\', HidUsage.KEY_BACKSLASH)
        put(';', HidUsage.KEY_SEMICOLON)
        put('\'', HidUsage.KEY_APOSTROPHE)
        put('`', HidUsage.KEY_GRAVE)
        put(',', HidUsage.KEY_COMMA)
        put('.', HidUsage.KEY_PERIOD)
        put('/', HidUsage.KEY_SLASH)
        put('\n', HidUsage.KEY_ENTER)
        put('\t', HidUsage.KEY_TAB)
    }

    /** 需要 Shift 才能输入的字符 → usage（配合 [CharMapping.needsShift] 使用）。 */
    val SHIFTED_CHAR_TO_USAGE: Map<Char, Int> = buildMap {
        ('A'..'Z').forEachIndexed { index, c -> put(c, HidUsage.KEY_A + index) }
        put('!', HidUsage.KEY_1)
        put('@', 0x1F)
        put('#', 0x20)
        put('$', 0x21)
        put('%', 0x22)
        put('^', 0x23)
        put('&', 0x24)
        put('*', 0x25)
        put('(', 0x26)
        put(')', HidUsage.KEY_0)
        put('_', HidUsage.KEY_MINUS)
        put('+', HidUsage.KEY_EQUAL)
        put('{', HidUsage.KEY_LEFT_BRACKET)
        put('}', HidUsage.KEY_RIGHT_BRACKET)
        put('|', HidUsage.KEY_BACKSLASH)
        put(':', HidUsage.KEY_SEMICOLON)
        put('"', HidUsage.KEY_APOSTROPHE)
        put('~', HidUsage.KEY_GRAVE)
        put('<', HidUsage.KEY_COMMA)
        put('>', HidUsage.KEY_PERIOD)
        put('?', HidUsage.KEY_SLASH)
    }

    /**
     * 字符 → HID usage（自动判断是否需要 Shift）。
     *
     * @return 映射结果；不可映射字符（如中文、emoji）返回 null，调用方应忽略。
     */
    fun usageForChar(char: Char): CharMapping? {
        CHAR_TO_USAGE[char]?.let { return CharMapping(it, needsShift = false) }
        SHIFTED_CHAR_TO_USAGE[char]?.let { return CharMapping(it, needsShift = true) }
        return null
    }

    /** 是否可映射为 HID 键盘 usage。 */
    fun isMappable(char: Char): Boolean = usageForChar(char) != null

    // ------------------------------------------------------------------ Consumer 媒体键

    /** Android keyCode → Consumer Page (0x0C) usage。 */
    val KEYCODE_TO_CONSUMER: Map<Int, Int> = mapOf(
        KeyEvent.KEYCODE_VOLUME_UP to HidUsage.CONSUMER_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN to HidUsage.CONSUMER_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE to HidUsage.CONSUMER_MUTE,
        KeyEvent.KEYCODE_MUTE to HidUsage.CONSUMER_MUTE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to HidUsage.CONSUMER_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY to HidUsage.CONSUMER_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PAUSE to HidUsage.CONSUMER_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_STOP to HidUsage.CONSUMER_STOP,
        KeyEvent.KEYCODE_MEDIA_NEXT to HidUsage.CONSUMER_SCAN_NEXT_TRACK,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS to HidUsage.CONSUMER_SCAN_PREVIOUS_TRACK,
        KeyEvent.KEYCODE_MEDIA_EJECT to HidUsage.CONSUMER_EJECT,
        KeyEvent.KEYCODE_BRIGHTNESS_UP to HidUsage.CONSUMER_BRIGHTNESS_UP,
        KeyEvent.KEYCODE_BRIGHTNESS_DOWN to HidUsage.CONSUMER_BRIGHTNESS_DOWN,
    )

    /**
     * 媒体键名 → Consumer usage（供键盘页 F 行媒体图标直接查表）。
     *
     * 命名与键盘页 F 行图标语义一一对应（见键盘页的双布局说明）。
     */
    val MEDIA_KEY_TO_CONSUMER: Map<String, Int> = mapOf(
        "volume_up" to HidUsage.CONSUMER_VOLUME_UP,
        "volume_down" to HidUsage.CONSUMER_VOLUME_DOWN,
        "mute" to HidUsage.CONSUMER_MUTE,
        "play_pause" to HidUsage.CONSUMER_PLAY_PAUSE,
        "next_track" to HidUsage.CONSUMER_SCAN_NEXT_TRACK,
        "previous_track" to HidUsage.CONSUMER_SCAN_PREVIOUS_TRACK,
        "stop" to HidUsage.CONSUMER_STOP,
        "brightness_up" to HidUsage.CONSUMER_BRIGHTNESS_UP,
        "brightness_down" to HidUsage.CONSUMER_BRIGHTNESS_DOWN,
        "power" to HidUsage.CONSUMER_POWER,
        "sleep" to HidUsage.CONSUMER_SLEEP,
        "eject" to HidUsage.CONSUMER_EJECT,
    )

    /** Android keyCode → Consumer usage；非媒体键返回 null。 */
    fun consumerUsageFor(androidKeyCode: Int): Int? = KEYCODE_TO_CONSUMER[androidKeyCode]

    /** 媒体键名 → Consumer usage。 */
    fun consumerUsageForName(name: String): Int? = MEDIA_KEY_TO_CONSUMER[name]

    // ------------------------------------------------------------------ 修饰键位图

    /**
     * `KeyEvent.getMetaState()` → HID 修饰键位图。
     *
     * meta state 无法区分左右（例如 META_CTRL_ON 同时覆盖左右 Ctrl），统一映射到左侧位；
     * 需要右侧修饰键时请直接用 [HidModifier] 常量自行组合。
     */
    fun modifiersFromMetaState(metaState: Int): Int {
        var modifiers = HidModifier.NONE
        if (metaState and KeyEvent.META_SHIFT_ON != 0) modifiers = modifiers or HidModifier.LEFT_SHIFT
        if (metaState and KeyEvent.META_ALT_ON != 0) modifiers = modifiers or HidModifier.LEFT_ALT
        if (metaState and KeyEvent.META_CTRL_ON != 0) modifiers = modifiers or HidModifier.LEFT_CTRL
        if (metaState and KeyEvent.META_META_ON != 0) modifiers = modifiers or HidModifier.LEFT_GUI
        return modifiers
    }

    /**
     * 一次性按下并松开某个字符（便捷方法，供调试 / 测试 / 文本输入用）。
     *
     * @return 8 字节引导键盘报告的「按下」与「松开」两份数据；不可映射时返回 null。
     */
    fun reportsForChar(char: Char): Pair<ByteArray, ByteArray>? {
        val mapping = usageForChar(char) ?: return null
        val modifiers = if (mapping.needsShift) HidModifier.LEFT_SHIFT else HidModifier.NONE
        val press = HidReportFactory.keyboard(modifiers, byteArrayOf(mapping.usage.toByte()))
        val release = HidReportFactory.keyboard(HidModifier.NONE, ByteArray(0))
        return press to release
    }
}
