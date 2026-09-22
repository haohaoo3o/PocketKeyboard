package com.pocketkeyboard.app.hid

/**
 * 本机蓝牙**广播名**策略（被控设备蓝牙菜单里看到的名字）。
 *
 * 目标：与手机系统名（如 `Redmi Note 11 Pro+`）明确区分——被控设备上看到的是
 * `PocketKeyboard-redmi`，一眼认出这是口袋键鼠而不是手机本体。
 *
 * 命名规则：`PocketKeyboard-<品牌>`，品牌取 `Build.BRAND` 小写（Redmi → `redmi`），
 * 只保留 `[a-z0-9-]`；品牌缺失时退化为纯 `PocketKeyboard`。生成结果同时用作
 * HID SDP 记录名与 `BluetoothAdapter.setName` 的目标名，保证各处一致。
 */
object BroadcastName {

    /** 名称前缀。 */
    const val PREFIX: String = "PocketKeyboard"

    /** SDP / GAP 名称上限 50 字节，留足余量。 */
    const val MAX_LENGTH = 40

    /**
     * 按品牌生成广播名（纯函数，便于单测）。
     *
     * @param brand `Build.BRAND` 一类的品牌串，可为 null
     */
    fun fromBrand(brand: String?): String {
        val suffix = brand.orEmpty()
            .trim()
            .lowercase()
            .filter { it in 'a'..'z' || it in '0'..'9' || it == '-' }
            .trim('-')
        val name = if (suffix.isEmpty()) PREFIX else "$PREFIX-$suffix"
        return name.take(MAX_LENGTH)
    }
}
