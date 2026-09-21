package com.pocketkeyboard.app.hid

/**
 * 复合键鼠 HID Report Descriptor（键盘 + Consumer 媒体键 + 鼠标/触控板）。
 *
 * ## 设计要点
 * 1. **三个顶级集合（Top-Level Collection）**：Keyboard、Consumer Control、Mouse。
 *    Windows 会为每个 TLC 建立独立 PDO（键盘走 KBDHID、鼠标走 MOUHID），
 *    iOS / macOS / Linux likewise 按集合分别绑定输入子系统，这是「一个设备同时是
 *    键盘和鼠标」的标准做法。
 * 2. **Report ID**：键盘 1、Consumer 2、鼠标 3、鼠标+水平滚轮 5（4 预留）。
 *    发送时 `sendReport(device, id, data)` 的 data **不含** Report ID，框架自动前置。
 * 3. **Boot Protocol 兼容**：键盘报告是标准 8 字节 `[mod, reserved, 6 keys]`，
 *    鼠标报告（ID 3）是标准 4 字节 `[buttons, X, Y, wheel]`。
 *    若主机切到 Boot Protocol，只会使用这两个报告的布局；Report ID 5 是纯扩展，
 *    仅在 Report Protocol 下被解析（主机忽略它即退化为「只有垂直滚轮」）。
 * 4. **键阵列范围 0x00–0xC7**：覆盖 87 键 TKL 全部按键 + 方向键 + F1–F24 + 小键盘
 *    （含小键盘「00」0xB0、「000」0xB1、「%」0xC4、「(」「)」0xB6/0xB7、「+/-」0xD7 等
 *    NUMPAD 模式需要的扩展 usage）。
 * 5. **LED 输出报告**：让主机回写 Num Lock / Caps Lock 状态（经
 *    `BluetoothHidDevice.Callback.onSetReport` 收到），便于键盘页显示 Caps Lock。
 *
 * ## 用法
 * 直接把 [HidReportDescriptor.bytes] 交给
 * `BluetoothHidDeviceAppSdpSettings(name, description, provider, BluetoothHidDevice.SUBCLASS1_COMBO, bytes)`，
 * 再由 `BluetoothHidDevice.registerApp(sdp, inQos, outQos, executor, callback)` 注册。
 *
 * 本文件用极简的 item 拼装器生成字节，便于单元测试逐项校验结构（见 `HidReportDescriptorTest`）。
 */
object HidReportDescriptor {

    /** 描述符字节（注册时原样交给 `BluetoothHidDeviceAppSdpSettings`）。 */
    val bytes: ByteArray = HidDescriptorBuilder().apply {

        // ==================================================================
        // 顶级集合 1：键盘（Report ID 1）
        // ==================================================================
        usagePage(HidPage.GENERIC_DESKTOP) // 0x05 0x01
        usage(0x06) // Usage (Keyboard)
        collection(COLLECTION_APPLICATION)
        reportId(HidReportFactory.REPORT_ID_KEYBOARD) // 0x85 0x01

        // --- 8 个修饰键位（LeftCtrl … RightGUI），1 bit / 键 ---
        usagePage(HidPage.KEYBOARD)
        usageMinimum(HidUsage.KEY_LEFT_CONTROL)
        usageMaximum(HidUsage.KEY_RIGHT_GUI)
        logicalMinimum(0)
        logicalMaximum(1)
        reportSize(1)
        reportCount(8)
        input(INPUT_DATA_VAR_ABS)

        // --- 保留字节（boot 格式要求，恒为 0）---
        reportCount(1)
        reportSize(8)
        input(INPUT_CONSTANT_ARRAY_ABS)

        // --- LED 输出报告（Num Lock … Kana + 3 bit 填充）---
        usagePage(HidPage.LEDS)
        usageMinimum(0x01) // Num Lock
        usageMaximum(0x05) // Kana
        reportSize(1)
        reportCount(5)
        output(OUTPUT_DATA_VAR_ABS)
        reportCount(1)
        reportSize(3)
        output(OUTPUT_CONSTANT_ARRAY_ABS)

        // --- 6 键阵列（6-key rollover）---
        usagePage(HidPage.KEYBOARD)
        logicalMinimum(0)
        logicalMaximum(HidReportFactory.MAX_KEY_USAGE)
        usageMinimum(0x00) // Reserved (no event indicated)
        usageMaximum(HidReportFactory.MAX_KEY_USAGE)
        reportSize(8)
        reportCount(HidReportFactory.MAX_KEYS_PER_REPORT)
        input(INPUT_DATA_ARRAY_ABS)
        endCollection()

        // ==================================================================
        // 顶级集合 2：Consumer Control（Report ID 2）
        //   音量 / 静音 / 播放暂停 / 上下曲 / 亮度 / AC Pan 等。
        //   采用「16 bit 数组、同一时刻只有一个 usage」的标准 Consumer Control 形式：
        //   Windows 文档明确要求 Consumer Control 不支持多键同按。
        // ==================================================================
        usagePage(HidPage.CONSUMER)
        usage(0x01) // Usage (Consumer Control)
        collection(COLLECTION_APPLICATION)
        reportId(HidReportFactory.REPORT_ID_CONSUMER) // 0x85 0x02
        logicalMinimum(0)
        logicalMaximum(0x03FF)
        usageMinimum(0x00)
        usageMaximum(0x03FF)
        reportSize(16)
        reportCount(1)
        input(INPUT_DATA_ARRAY_ABS)
        endCollection()

        // ==================================================================
        // 顶级集合 3：鼠标 / 触控板（Report ID 3 与 5）
        // ==================================================================
        usagePage(HidPage.GENERIC_DESKTOP)
        usage(0x02) // Usage (Mouse)
        collection(COLLECTION_APPLICATION)
        reportId(HidReportFactory.REPORT_ID_MOUSE) // 0x85 0x03
        usage(0x01) // Usage (Pointer)
        collection(COLLECTION_PHYSICAL)

        // --- 3 个物理按键（左 / 右 / 中；接口只暴露左右，中键位保留兼容）---
        usagePage(HidPage.BUTTON)
        usageMinimum(0x01)
        usageMaximum(0x03)
        logicalMinimum(0)
        logicalMaximum(1)
        reportSize(1)
        reportCount(3)
        input(INPUT_DATA_VAR_ABS)
        reportCount(1)
        reportSize(5) // 补齐到 1 字节
        input(INPUT_CONSTANT_VAR_ABS)

        // --- X / Y 相对位移 + 垂直滚轮 ---
        usagePage(HidPage.GENERIC_DESKTOP)
        usage(0x30) // X
        usage(0x31) // Y
        usage(0x38) // Wheel
        logicalMinimum(-127)
        logicalMaximum(127)
        reportSize(8)
        reportCount(3)
        input(INPUT_DATA_VAR_RELATIVE)
        endCollection() // Physical

        // --- Report ID 5：水平滚轮（AC Pan），仅在需要横向滚动时使用 ---
        reportId(HidReportFactory.REPORT_ID_MOUSE_PAN) // 0x85 0x05
        usagePage(HidPage.CONSUMER)
        usage(HidUsage.CONSUMER_AC_PAN) // 0x0A 0x38 0x02（2 字节 usage 0x0238）
        logicalMinimum(-127)
        logicalMaximum(127)
        reportSize(8)
        reportCount(1)
        input(INPUT_DATA_VAR_RELATIVE)
        endCollection()
    }.toByteArray()

    // ---------------------------------------------------------------- item 常量

    const val COLLECTION_PHYSICAL = 0x00
    const val COLLECTION_APPLICATION = 0x01

    const val INPUT_DATA_ARRAY_ABS = 0x00
    const val INPUT_CONSTANT_ARRAY_ABS = 0x01
    const val INPUT_DATA_VAR_ABS = 0x02
    const val INPUT_CONSTANT_VAR_ABS = 0x03
    const val INPUT_DATA_VAR_RELATIVE = 0x06

    const val OUTPUT_DATA_VAR_ABS = 0x02
    const val OUTPUT_CONSTANT_ARRAY_ABS = 0x01
}

/** HID Usage Page 常量（与 [HidUsage] 中的页面号一致，单独放便于描述符阅读）。 */
internal object HidPage {
    const val GENERIC_DESKTOP = 0x01
    const val LEDS = 0x08
    const val BUTTON = 0x09
    const val KEYBOARD = 0x07
    const val CONSUMER = 0x0C
}

/**
 * 极简 HID Report Descriptor 字节拼装器。
 *
 * 只实现本 descriptor 用到的 short item 编码：`bSize | (bType << 2) | (bTag << 4)`，
 * 其中 bSize 为数据字节数编码（0→0 字节、1→1 字节、2→2 字节、3→4 字节），
 * bType：0=Main、1=Global、2=Local。
 */
internal class HidDescriptorBuilder {

    private val out = ArrayList<Byte>()

    fun usagePage(page: Int) = globalItem(tag = 0x00, value = page)

    fun reportId(id: Int) = globalItem(tag = 0x08, value = id)

    fun logicalMinimum(value: Int) = globalItem(tag = 0x01, value = value)

    fun logicalMaximum(value: Int) = globalItem(tag = 0x02, value = value)

    fun reportSize(size: Int) = globalItem(tag = 0x07, value = size)

    fun reportCount(count: Int) = globalItem(tag = 0x09, value = count)

    fun usage(value: Int) = localItem(tag = 0x00, value = value)

    fun usageMinimum(value: Int) = localItem(tag = 0x01, value = value)

    fun usageMaximum(value: Int) = localItem(tag = 0x02, value = value)

    fun collection(value: Int) = mainItem(tag = 0x0A, value = value)

    fun endCollection() {
        out.add(0xC0.toByte()) // (0 << 2) | Main | (12 << 4)
    }

    fun input(value: Int) = mainItem(tag = 0x08, value = value)

    fun output(value: Int) = mainItem(tag = 0x09, value = value)

    fun toByteArray(): ByteArray = out.toByteArray()

    // ---------------------------------------------------------------- item 编码

    private fun globalItem(tag: Int, value: Int) = emit(tag = tag, type = 0x01, value = value)

    private fun localItem(tag: Int, value: Int) = emit(tag = tag, type = 0x02, value = value)

    private fun mainItem(tag: Int, value: Int) = emit(tag = tag, type = 0x00, value = value)

    private fun emit(tag: Int, type: Int, value: Int) {
        val payload = encodeValue(value)
        // short item 首字节：bit0-1 = bSize（数据字节数编码）、bit2-3 = bType、bit4-7 = bTag
        val header = (payload.size and 0x03) or (type shl 2) or (tag shl 4)
        out.add((header and 0xFF).toByte())
        payload.forEach { out.add(it) }
    }

    /** 按 HID 规范的 1/2/4 字节定长编码。 */
    private fun encodeValue(value: Int): ByteArray = when {
        // 注意：0 也必须编码成 1 个字节。HID short item 的 bSize = 0 表示「本项没有数据」，
        // 而 Logical Minimum (0) / Collection (Physical) / Usage Minimum (0) 这些项
        // 都需要显式的 0 数据字节；偷懒编码成 0 字节会生成 `0x14`、`0xA0` 这类裸头，
        // 主机解析器会把它当成非法项或丢弃，整个描述符失效。
        value in -128..255 -> byteArrayOf((value and 0xFF).toByte())
        value in -32768..65535 -> byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
        )
        else -> byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte(),
        )
    }
}
