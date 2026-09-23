package com.pocketkeyboard.app.hid

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [BroadcastName] 命名策略单测：广播名与手机系统名区分（`PocketKeyboard_<品牌>`，
 * 品牌按系统品牌自动适配、首字母大写）。
 */
class BroadcastNameTest {

    @Test
    fun `brand adapts automatically with title case`() {
        // 用户示例：读取到 Huawei → PocketKeyboard_Huawei
        assertEquals("PocketKeyboard_Huawei", BroadcastName.fromBrand("HUAWEI"))
        assertEquals("PocketKeyboard_Huawei", BroadcastName.fromBrand("Huawei"))
        assertEquals("PocketKeyboard_Redmi", BroadcastName.fromBrand("Redmi"))
        assertEquals("PocketKeyboard_Redmi", BroadcastName.fromBrand("redmi"))
        assertEquals("PocketKeyboard_Redmi", BroadcastName.fromBrand(" REDMI "))
    }

    @Test
    fun `missing brand falls back to the prefix`() {
        assertEquals(BroadcastName.PREFIX, BroadcastName.fromBrand(null))
        assertEquals(BroadcastName.PREFIX, BroadcastName.fromBrand(""))
        assertEquals(BroadcastName.PREFIX, BroadcastName.fromBrand("  "))
    }

    @Test
    fun `unsafe characters are stripped and words are joined`() {
        assertEquals("PocketKeyboard_Xiaomi23", BroadcastName.fromBrand("Xiaomi 23!"))
        assertEquals(BroadcastName.PREFIX, BroadcastName.fromBrand("!!!--"))
    }

    @Test
    fun `name length stays within the sdp limit`() {
        val name = BroadcastName.fromBrand("a".repeat(200))

        assertEquals(BroadcastName.MAX_LENGTH, name.length)
    }
}
