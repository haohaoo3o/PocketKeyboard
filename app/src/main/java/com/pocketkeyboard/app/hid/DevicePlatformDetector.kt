package com.pocketkeyboard.app.hid

/**
 * 设备平台判定（纯 Kotlin 启发式，可单测）。
 *
 * 判定依据（按优先级）：
 * 1. **设备名特征**：iOS/iPadOS 17+ 与 macOS 普遍开启 MAC 随机化，经典蓝牙配对时拿到的
 *    BD_ADDR 可能已不是 Apple OUI，因此名称是第一依据。
 * 2. **OUI 前缀**：名称为空时用 Apple 的 OUI 段兜底。
 *
 * 这是一个**启发式**判定，做不到 100% 准确；UI 侧在首次连接时仍会让用户确认平台
 * （见配对页的平台选择 Dialog），本类只负责给出默认猜测。
 */
object DevicePlatformDetector {

    /**
     * 常见 Apple OUI 前 3 字节（IEEE MA-L 注册段）。
     *
     * 只是高频子集而非全表：全表见 IEEE 注册管理机构（检索 "Apple, Inc."）。
     * 未命中不代表不是苹果设备。
     */
    private val APPLE_OUIS: Set<String> = setOf(
        "00:03:93", "00:0A:27", "00:0A:95", "00:0D:93", "00:10:FA", "00:11:24",
        "00:14:51", "00:16:CB", "00:17:F2", "00:19:E3", "00:1B:63", "00:1C:B3",
        "00:1D:4F", "00:1E:52", "00:1E:C2", "00:1F:5B", "00:1F:F3", "00:21:E9",
        "00:22:41", "00:23:12", "00:23:32", "00:23:6C", "00:23:DF", "00:24:36",
        "00:25:00", "00:25:4B", "00:25:BC", "00:26:08", "00:26:4A", "00:26:B0",
        "00:26:BB", "00:50:E4", "00:88:65", "04:0C:CE", "08:74:02", "0C:30:21",
        "10:1C:0C", "10:93:E9", "14:99:E2", "28:CF:E9", "34:C0:59", "3C:07:54",
        "40:98:AD", "40:B3:CD", "44:FB:42", "4C:8D:79", "4C:B1:99", "54:26:96",
        "54:AE:27", "5C:F7:E6", "60:F8:1D", "6C:8D:C1", "70:48:0F", "78:31:C1",
        "7C:6D:62", "80:BE:05", "84:38:35", "90:B2:1F", "98:00:C6", "9C:F4:8B",
        "A4:5E:60", "AC:BC:32", "B0:48:1A", "B8:09:8A", "BC:6C:21", "C4:B3:01",
        "C8:2A:14", "CC:08:8D", "D0:81:7A", "D4:90:9C", "DC:2B:2A", "E0:AC:CB",
        "E4:25:E7", "E8:8D:28", "F0:99:BF", "F4:F1:5A",
    )

    /** 设备名中的 Apple 关键词（小写比较）。 */
    private val APPLE_NAME_KEYWORDS: List<String> = listOf(
        "iphone", "ipad", "ipod", "macbook", "mac mini", "imac", "mac pro", "mac studio",
        "apple", "airpods", "airtag", "homepod", "magic keyboard", "magic mouse",
        "magic trackpad", "apple tv", "apple watch", "beats",
    )

    /**
     * 判定平台。
     *
     * @param name 主机名（可能为 null / 空）
     * @param address BD_ADDR，如 "AA:BB:CC:DD:EE:FF"
     */
    fun detect(name: String?, address: String): DevicePlatformHint {
        val normalizedName = name?.trim()?.lowercase().orEmpty()
        if (normalizedName.isNotEmpty()) {
            if (APPLE_NAME_KEYWORDS.any { normalizedName.contains(it) }) {
                return DevicePlatformHint.APPLE
            }
            // 名称存在但没有任何 Apple 特征：直接判为「其他」，
            // 因为 iOS / macOS 的设备名基本都带 Apple / iPhone / iPad / Mac 字样。
            return DevicePlatformHint.OTHER
        }
        return if (isAppleOui(address)) DevicePlatformHint.APPLE else DevicePlatformHint.UNKNOWN
    }

    /** 地址前 3 字节是否为 Apple OUI。 */
    fun isAppleOui(address: String): Boolean {
        val prefix = address.take(8).uppercase()
        return prefix in APPLE_OUIS
    }
}
