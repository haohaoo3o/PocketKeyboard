package com.pocketkeyboard.app.hid

/**
 * HID 输入报告构造（纯 Kotlin，无 Android 依赖，可完整单元测试）。
 *
 * ## 报告 ID 与 report descriptor 的对应关系
 * Report ID 由 [HidReportDescriptor] 声明；`BluetoothHidDevice.sendReport(device, id, data)`
 * 发送时**不需要**在 data 里带 Report ID（框架会自动前置），见
 * `BluetoothHidDevice.sendReport` javadoc：`@param data Report data, not including Report ID`。
 *
 * | Report ID | 长度 | 布局 |
 * | --- | --- | --- |
 * | [REPORT_ID_KEYBOARD] = 1 | 8 | `[modifiers, reserved, key1..key6]`（boot 键盘格式） |
 * | [REPORT_ID_CONSUMER] = 2 | 2 | `[usage_lo, usage_hi]`（Consumer Page usage，小端） |
 * | [REPORT_ID_MOUSE] = 3 | 4 | `[buttons, X, Y, wheel]`（boot 鼠标格式） |
 * | [REPORT_ID_MOUSE_PAN] = 5 | 5 | `[buttons, X, Y, wheel, pan]`（含水平滚轮） |
 *
 * Report ID 4 故意留空：方便后续插入新报告而不改变已有 ID（主机按 ID 缓存报告格式）。
 */
object HidReportFactory {

    /** 键盘输入报告 ID。 */
    const val REPORT_ID_KEYBOARD = 1

    /** Consumer 媒体键输入报告 ID。 */
    const val REPORT_ID_CONSUMER = 2

    /** 鼠标输入报告 ID（4 字节，boot 兼容）。 */
    const val REPORT_ID_MOUSE = 3

    /** 鼠标 + 水平滚轮（AC Pan）输入报告 ID。 */
    const val REPORT_ID_MOUSE_PAN = 5

    /** 引导键盘报告固定长度。 */
    const val KEYBOARD_REPORT_SIZE = 8

    /** 单次最多同时按下的普通按键数（6-key rollover）。 */
    const val MAX_KEYS_PER_REPORT = 6

    /**
     * 报告键阵列允许的最大 usage（见 [HidReportDescriptor] 的 Usage Maximum）。
     *
     * 取 0xFF（Keyboard/Keypad Page 0x07 全范围）而不是原先的 0xC7：小键盘扩展键
     * `Keypad +/-` 的 usage 是 0xD7，先前的上限会把它静默丢掉（[normalizeKeys] 过滤 +
     * 描述符 Usage Maximum 双重拦截），数字小键盘的「+/-」键在真机上按不出任何字符。
     */
    const val MAX_KEY_USAGE = 0xFF

    /** 相对位移 / 滚轮单字节范围。 */
    const val AXIS_MIN = -127
    const val AXIS_MAX = 127

    /** 鼠标按键位：bit0 左键、bit1 右键、bit2 中键（中键当前接口未暴露，保留兼容）。 */
    const val BUTTON_LEFT = 0x01
    const val BUTTON_RIGHT = 0x02
    const val BUTTON_MIDDLE = 0x04

    // ------------------------------------------------------------------ 键盘

    /**
     * 构造 8 字节引导键盘报告。
     *
     * @param modifiers 修饰键位图（见 [HidModifier]），只取低 8 位。
     * @param keyCodes HID Usage ID 数组；空数组 = 松开全部按键。
     * @return 固定 8 字节：`[modifiers, 0x00, k1, k2, k3, k4, k5, k6]`，
     *         不足 6 个用 0x00 填充，超过 6 个去重后截断。
     */
    fun keyboard(modifiers: Int, keyCodes: ByteArray): ByteArray {
        val report = ByteArray(KEYBOARD_REPORT_SIZE)
        report[0] = (modifiers and 0xFF).toByte()
        report[1] = 0x00 // Reserved (no event indicated)
        val keys = normalizeKeys(keyCodes)
        for (index in keys.indices) {
            report[2 + index] = keys[index]
        }
        return report
    }

    /** 松开全部按键的空报告。 */
    fun keyboardRelease(): ByteArray = keyboard(HidModifier.NONE, ByteArray(0))

    /**
     * 归一化按键数组：丢弃 0x00 与超范围值、去重、保持按下顺序、截断到 6 个。
     *
     * HID 规定同一 usage 在一个报告里只能出现一次，重复上报会让部分主机行为异常。
     */
    private fun normalizeKeys(keyCodes: ByteArray): List<Byte> {
        val seen = LinkedHashSet<Byte>()
        for (raw in keyCodes) {
            val usage = raw.toInt() and 0xFF
            if (usage == 0x00) continue
            if (usage > MAX_KEY_USAGE) continue
            seen.add(usage.toByte())
            if (seen.size >= MAX_KEYS_PER_REPORT) break
        }
        return seen.toList()
    }

    // ------------------------------------------------------------------ Consumer

    /**
     * 构造 Consumer Page 报告（2 字节，小端）。
     *
     * @param usage Consumer Page Usage ID，0 表示松开。见 [HidUsage.CONSUMER_MUTE] 等。
     */
    fun consumer(usage: Int): ByteArray {
        val value = usage and 0xFFFF
        return byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
    }

    /** 松开 Consumer 键（发送 usage 0）。 */
    fun consumerRelease(): ByteArray = consumer(0)

    // ------------------------------------------------------------------ 鼠标 / 触控板

    /**
     * 构造 4 字节鼠标报告（Report ID 3）。
     *
     * @param buttons 按键位图（[BUTTON_LEFT] / [BUTTON_RIGHT] / [BUTTON_MIDDLE]）。
     * @param dx 水平位移（正向右），裁剪到 [AXIS_MIN]..[AXIS_MAX]。
     * @param dy 垂直位移（正向下），裁剪到 [AXIS_MIN]..[AXIS_MAX]。
     * @param wheel 垂直滚轮（正向下），裁剪到 [AXIS_MIN]..[AXIS_MAX]。
     */
    fun mouse(buttons: Int, dx: Int, dy: Int, wheel: Int): ByteArray = byteArrayOf(
        (buttons and 0x07).toByte(),
        clampAxis(dx).toByte(),
        clampAxis(dy).toByte(),
        clampAxis(wheel).toByte(),
    )

    /**
     * 构造 5 字节鼠标报告（Report ID 5），在 [mouse] 基础上追加水平滚轮 AC Pan。
     *
     * 仅在确实需要水平滚动时使用（见 `HidDeviceTransport.sendScroll`），
     * 这样纯垂直滚动的常见路径始终走 boot 兼容的 4 字节报告。
     */
    fun mouseWithPan(buttons: Int, dx: Int, dy: Int, wheel: Int, pan: Int): ByteArray =
        mouse(buttons, dx, dy, wheel) + clampAxis(pan).toByte()

    /** 位移 / 滚轮值裁剪到单字节有符号范围。 */
    fun clampAxis(value: Int): Int = when {
        value > AXIS_MAX -> AXIS_MAX
        value < AXIS_MIN -> AXIS_MIN
        else -> value
    }

    /** 按键位图裁剪到 3 位。 */
    fun clampButtons(buttons: Int): Int = buttons and 0x07
}
