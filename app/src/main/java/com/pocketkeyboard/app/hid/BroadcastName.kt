package com.pocketkeyboard.app.hid

/**
 * 本机蓝牙**广播名**策略（被控设备蓝牙菜单里看到的名字）。
 *
 * 目标：与手机系统名（如 `Redmi Note 11 Pro+`）明确区分——被控设备上看到的是
 * `PocketKeyboard_Redmi`，一眼认出这是口袋键鼠而不是手机本体。
 *
 * 命名规则：`PocketKeyboard_<品牌>`，品牌**按系统品牌自动适配**（实测反馈：不能
 * 固定跟 redmi）——取 `Build.BRAND` 规范成「每个词首字母大写、其余小写」并去掉
 * 词间分隔符：HUAWEI → `Huawei`（→ `PocketKeyboard_Huawei`）、Redmi → `Redmi`。
 * 品牌缺失时退化为纯 `PocketKeyboard`。生成结果同时用作 HID SDP 记录名与
 * `BluetoothAdapter.setName` 的目标名，保证各处一致。
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
            .split(Regex("[^A-Za-z0-9]+"))
            .filter { it.isNotEmpty() }
            .joinToString("") { token ->
                token.lowercase().replaceFirstChar { it.uppercaseChar() }
            }
        val name = if (suffix.isEmpty()) PREFIX else "${PREFIX}_$suffix"
        return name.take(MAX_LENGTH)
    }
}
