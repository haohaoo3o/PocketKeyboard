package com.pocketkeyboard.app.hid

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [BroadcastName] 命名策略单测：广播名与手机系统名区分（`PocketKeyboard-<品牌>`）。
 */
class BroadcastNameTest {

    @Test
    fun `redmi brand produces pocket keyboard redmi`() {
        assertEquals("PocketKeyboard-redmi", BroadcastName.fromBrand("Redmi"))
        assertEquals("PocketKeyboard-redmi", BroadcastName.fromBrand("redmi"))
        assertEquals("PocketKeyboard-redmi", BroadcastName.fromBrand(" REDMI "))
    }

    @Test
    fun `missing brand falls back to the prefix`() {
        assertEquals(BroadcastName.PREFIX, BroadcastName.fromBrand(null))
        assertEquals(BroadcastName.PREFIX, BroadcastName.fromBrand(""))
        assertEquals(BroadcastName.PREFIX, BroadcastName.fromBrand("  "))
    }

    @Test
    fun `unsafe characters are stripped`() {
        assertEquals("PocketKeyboard-xiaomi23", BroadcastName.fromBrand("Xiaomi 23!"))
        assertEquals(BroadcastName.PREFIX, BroadcastName.fromBrand("!!!--"))
    }

    @Test
    fun `name length stays within the sdp limit`() {
        val name = BroadcastName.fromBrand("a".repeat(200))

        assertEquals(BroadcastName.MAX_LENGTH, name.length)
    }
}
