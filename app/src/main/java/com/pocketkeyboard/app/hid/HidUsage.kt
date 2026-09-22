package com.pocketkeyboard.app.hid

/**
 * HID Usage Tables 常量（USB HID Usage Tables 1.12 / HUTRR41，蓝牙 HID 复用同一张表）。
 *
 * 只放「本 App 真正会用到的」usage，全部为 `Int`（usage ID 0x0000–0xFFFF）。
 * 本文件为纯 Kotlin，不依赖任何 Android 类，便于单元测试。
 *
 * 取值依据：
 * - Keyboard/Keypad Page (0x07)：与 FreeBSD `share/misc/usb_hid_usages`（源自 USB-IF HUT）逐条核对。
 * - Consumer Page (0x0C)：音量/播放/静音 0xE2/0xE9/0xEA/0xCD/0xB5/0xB6；亮度 0x6F/0x70 见
 *   USB-IF HUTRR41《Display Brightness Controls》与 Microsoft《显示亮度控制》文档。
 */
object HidUsage {

    /** Usage Page：键盘 / 小键盘。 */
    const val PAGE_KEYBOARD = 0x07

    /** Usage Page：Consumer（媒体键、亮度、浏览器控制等）。 */
    const val PAGE_CONSUMER = 0x0C

    /** Usage Page：Generic Desktop（鼠标 X/Y、滚轮、Pointer）。 */
    const val PAGE_GENERIC_DESKTOP = 0x01

    // ---------------------------------------------------------------- 修饰键位
    //
    // 引导（boot）键盘报告第 1 字节的位图，与 [HidModifier] 一一对应：
    //   bit0 LeftCtrl  bit1 LeftShift  bit2 LeftAlt   bit3 LeftGUI
    //   bit4 RightCtrl bit5 RightShift bit6 RightAlt  bit7 RightGUI

    /** Keyboard Page usage：LeftControl。 */
    const val KEY_LEFT_CONTROL = 0xE0

    /** Keyboard Page usage：LeftShift。 */
    const val KEY_LEFT_SHIFT = 0xE1

    /** Keyboard Page usage：LeftAlt。 */
    const val KEY_LEFT_ALT = 0xE2

    /** Keyboard Page usage：LeftGUI（macOS 的 Command / Windows 的 Win）。 */
    const val KEY_LEFT_GUI = 0xE3

    /** Keyboard Page usage：RightControl。 */
    const val KEY_RIGHT_CONTROL = 0xE4

    /** Keyboard Page usage：RightShift。 */
    const val KEY_RIGHT_SHIFT = 0xE5

    /** Keyboard Page usage：RightAlt。 */
    const val KEY_RIGHT_ALT = 0xE6

    /** Keyboard Page usage：RightGUI。 */
    const val KEY_RIGHT_GUI = 0xE7

    // ---------------------------------------------------------------- 常用按键
    // （87 键 TKL + 小键盘 + 国际键的完整表在 [HidUsageMapper]，这里只列高频常量）

    const val KEY_A = 0x04
    const val KEY_Z = 0x1D
    const val KEY_1 = 0x1E
    const val KEY_0 = 0x27
    const val KEY_ENTER = 0x28
    const val KEY_ESCAPE = 0x29

    /** Backspace（Keyboard DELETE (Backspace)）。 */
    const val KEY_BACKSPACE = 0x2A

    const val KEY_TAB = 0x2B
    const val KEY_SPACE = 0x2C
    const val KEY_MINUS = 0x2D
    const val KEY_EQUAL = 0x2E
    const val KEY_LEFT_BRACKET = 0x2F
    const val KEY_RIGHT_BRACKET = 0x30
    const val KEY_BACKSLASH = 0x31
    const val KEY_NON_US_HASH = 0x32
    const val KEY_SEMICOLON = 0x33
    const val KEY_APOSTROPHE = 0x34
    const val KEY_GRAVE = 0x35
    const val KEY_COMMA = 0x36
    const val KEY_PERIOD = 0x37
    const val KEY_SLASH = 0x38
    const val KEY_CAPS_LOCK = 0x39

    const val KEY_F1 = 0x3A
    const val KEY_F12 = 0x45

    /** PrintScreen。 */
    const val KEY_PRINT_SCREEN = 0x46

    const val KEY_SCROLL_LOCK = 0x47

    /** Pause / Break。 */
    const val KEY_PAUSE = 0x48

    const val KEY_INSERT = 0x49
    const val KEY_HOME = 0x4A
    const val KEY_PAGE_UP = 0x4B
    const val KEY_DELETE_FORWARD = 0x4C
    const val KEY_END = 0x4D
    const val KEY_PAGE_DOWN = 0x4E
    const val KEY_RIGHT_ARROW = 0x4F
    const val KEY_LEFT_ARROW = 0x50
    const val KEY_DOWN_ARROW = 0x51
    const val KEY_UP_ARROW = 0x52

    const val KEY_NUM_LOCK = 0x53
    const val KEY_KP_DIVIDE = 0x54
    const val KEY_KP_MULTIPLY = 0x55
    const val KEY_KP_SUBTRACT = 0x56
    const val KEY_KP_ADD = 0x57
    const val KEY_KP_ENTER = 0x58

    /** 小键盘 1（与 End 共用）。 */
    const val KEY_KP_1 = 0x59

    /** 小键盘 9。 */
    const val KEY_KP_9 = 0x61

    /** 小键盘 0（与 Insert 共用）。 */
    const val KEY_KP_0 = 0x62

    /** 小键盘 .（与 Delete 共用）。 */
    const val KEY_KP_DECIMAL = 0x63

    /** Keyboard Non-US \ and |。 */
    const val KEY_NON_US_BACKSLASH = 0x64

    /** Keyboard Application（菜单键 / 上下文菜单）。 */
    const val KEY_APPLICATION = 0x65

    const val KEY_F13 = 0x68
    const val KEY_F24 = 0x73

    /** Keypad =。 */
    const val KEY_KP_EQUAL = 0x67

    /** Keypad Comma（部分欧版 / 巴西布局）。 */
    const val KEY_KP_COMMA = 0x85

    /** Keypad 00（数字小键盘「00」，见 [HidUsageMapper.NUMPAD_KEYS]）。 */
    const val KEY_KP_00 = 0xB0

    /** Keypad 000。 */
    const val KEY_KP_000 = 0xB1

    /** Keypad ( */
    const val KEY_KP_LEFT_PARENTHESIS = 0xB6

    /** Keypad ) */
    const val KEY_KP_RIGHT_PARENTHESIS = 0xB7

    /** Keypad % */
    const val KEY_KP_PERCENT = 0xC4

    /** Keypad +/- */
    const val KEY_KP_PLUS_MINUS = 0xD7

    // ---------------------------------------------------------------- Consumer Page (0x0C)

    /** Consumer：Mute（静音）。 */
    const val CONSUMER_MUTE = 0x00E2

    /** Consumer：Volume Increment（音量增大）。 */
    const val CONSUMER_VOLUME_UP = 0x00E9

    /** Consumer：Volume Decrement（音量减小）。 */
    const val CONSUMER_VOLUME_DOWN = 0x00EA

    /** Consumer：Play/Pause（播放 / 暂停）。 */
    const val CONSUMER_PLAY_PAUSE = 0x00CD

    /** Consumer：Scan Next Track（下一首）。 */
    const val CONSUMER_SCAN_NEXT_TRACK = 0x00B5

    /** Consumer：Scan Previous Track（上一首）。 */
    const val CONSUMER_SCAN_PREVIOUS_TRACK = 0x00B6

    /** Consumer：Stop。 */
    const val CONSUMER_STOP = 0x00B7

    /** Consumer：Eject。 */
    const val CONSUMER_EJECT = 0x00B8

    /**
     * Consumer：AC Fast Forward（快进）。
     *
     * 取值依据：Consumer Page (0x0C) 0xB3 = Fast Forward、0xB4 = Rewind
     * （与 FreeBSD `share/misc/usb_hid_usages` 逐条核对，注意 0xB3/0xB4 的顺序是
     * 「快进在前、后退在后」，与部分中文资料的写法相反）。
     */
    const val CONSUMER_AC_FAST_FORWARD = 0x00B3

    /** Consumer：AC Rewind（后退 / 快退）。 */
    const val CONSUMER_AC_REWIND = 0x00B4

    /**
     * Consumer：Display Brightness Increment（亮度增大）。
     *
     * 平台差异（重要，写在这里供键盘页查阅）：
     * - iOS / iPadOS / macOS：Consumer Page 0x6F / 0x70 会被系统直接识别，可调屏幕亮度。
     * - Windows：只有笔记本（电池供电）且 Windows 8+ 才响应（Microsoft《显示亮度控制》），
     *   外接显示器无效；更多 OEM 走 ACPI 通知而非 HID。
     * - Linux：桌面环境一般映射为 XF86MonBrightnessUp / Down。
     *
     * 因此「亮度键」在 Windows 上通常需要厂商自有方案（ACPI / WMI），
     * HID usage 只是尽力而为；iOS / macOS 才是主战场。
     */
    const val CONSUMER_BRIGHTNESS_UP = 0x006F

    /** Consumer：Display Brightness Decrement（亮度减小），平台差异见 [CONSUMER_BRIGHTNESS_UP]。 */
    const val CONSUMER_BRIGHTNESS_DOWN = 0x0070

    /** Consumer：Power（电源键）。 */
    const val CONSUMER_POWER = 0x0030

    /** Consumer：Sleep。 */
    const val CONSUMER_SLEEP = 0x0032

    /** Consumer：AC Home（浏览器主页 / 部分主机的 Home 键）。 */
    const val CONSUMER_AC_HOME = 0x0223

    /** Consumer：AC Back。 */
    const val CONSUMER_AC_BACK = 0x0224

    /** Consumer：AC Forward。 */
    const val CONSUMER_AC_FORWARD = 0x0225

    /** Consumer：AC Refresh。 */
    const val CONSUMER_AC_REFRESH = 0x0227

    /**
     * Consumer：AC Pan（水平滚轮 / 触控板横向滚动）。
     *
     * Windows 的 Precision Touchpad 与多数 Linux 桌面环境把「水平滚动」识别为 AC Pan；
     * macOS 的横向滚动走自有触摸板解析，不依赖该 usage。
     */
    const val CONSUMER_AC_PAN = 0x0238

    /** Consumer：AL Email Reader。 */
    const val CONSUMER_AL_EMAIL_READER = 0x018A

    /** Consumer：AL Calculator。 */
    const val CONSUMER_AL_CALCULATOR = 0x0192
}

/** 修饰键位图工具（boot 键盘报告第 1 字节）。 */
object HidModifier {

    const val NONE = 0x00
    const val LEFT_CTRL = 0x01
    const val LEFT_SHIFT = 0x02
    const val LEFT_ALT = 0x04
    const val LEFT_GUI = 0x08
    const val RIGHT_CTRL = 0x10
    const val RIGHT_SHIFT = 0x20
    const val RIGHT_ALT = 0x40

    /** bit7（0x80）。 */
    const val RIGHT_GUI = 0x80

    /** 8 个修饰键 usage 全在这里，用于「usage ↔ 位」互查。 */
    val USAGE_TO_BIT: Map<Int, Int> = mapOf(
        HidUsage.KEY_LEFT_CONTROL to LEFT_CTRL,
        HidUsage.KEY_LEFT_SHIFT to LEFT_SHIFT,
        HidUsage.KEY_LEFT_ALT to LEFT_ALT,
        HidUsage.KEY_LEFT_GUI to LEFT_GUI,
        HidUsage.KEY_RIGHT_CONTROL to RIGHT_CTRL,
        HidUsage.KEY_RIGHT_SHIFT to RIGHT_SHIFT,
        HidUsage.KEY_RIGHT_ALT to RIGHT_ALT,
        HidUsage.KEY_RIGHT_GUI to RIGHT_GUI,
    )

    /** 该 usage 是否为修饰键。 */
    fun isModifier(usage: Int): Boolean = USAGE_TO_BIT.containsKey(usage)

    /** 修饰键 usage → 位图位；非修饰键返回 0。 */
    fun bitFor(usage: Int): Int = USAGE_TO_BIT[usage] ?: 0
}
