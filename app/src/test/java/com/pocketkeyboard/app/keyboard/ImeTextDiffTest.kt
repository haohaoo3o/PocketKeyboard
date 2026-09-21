package com.pocketkeyboard.app.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「系统输入法 → HID」文本差分的纯 JVM 单测（Bug 6 核心链路）。
 *
 * 覆盖：追加字符逐条提交、删除按字符数补发退格、先删后填的顺序、组词前后文本
 * 的一次性差分、零宽空格哨兵上的退格、空差异、以及 Emoji / 代理对这类
 * 「可提交但不可映射」字符仍然原样产出（映射丢弃由调用方负责）。
 */
class ImeTextDiffTest {

    @Test
    fun `same text produces no actions`() {
        assertTrue(ImeTextDiff.diff("", "").isEmpty())
        assertTrue(ImeTextDiff.diff("\u200B", "\u200B").isEmpty())
    }

    @Test
    fun `appended characters are committed one by one`() {
        val actions = ImeTextDiff.diff("\u200B", "\u200Bab")
        assertEquals(
            listOf(ImeKeyAction.Commit('a'), ImeKeyAction.Commit('b')),
            actions,
        )
    }

    @Test
    fun `shortened text emits one backspace per removed char`() {
        val actions = ImeTextDiff.diff("\u200Babc", "\u200B")
        assertEquals(listOf(ImeKeyAction.Backspace(3)), actions)
    }

    @Test
    fun `backspace on empty sentinel field is visible as a removal`() {
        // 输入法在空框上按退格：哨兵被删掉 → 文本由 1 个字符变 0 → 一次退格
        val actions = ImeTextDiff.diff("\u200B", "")
        assertEquals(listOf(ImeKeyAction.Backspace(1)), actions)
    }

    @Test
    fun `replacement deletes before committing`() {
        // 中间改动（如光标前插入）：公共前缀之后先删后填
        val actions = ImeTextDiff.diff("ab", "axb")
        assertEquals(
            listOf(ImeKeyAction.Backspace(1), ImeKeyAction.Commit('x'), ImeKeyAction.Commit('b')),
            actions,
        )
    }

    @Test
    fun `typing after sentinel keeps sentinel untouched`() {
        // 哨兵恒定留在最前面：追加字符从公共前缀之后开始算
        val actions = ImeTextDiff.diff("\u200B", "\u200Bhi!")
        assertEquals(
            listOf(
                ImeKeyAction.Commit('h'),
                ImeKeyAction.Commit('i'),
                ImeKeyAction.Commit('!'),
            ),
            actions,
        )
    }

    @Test
    fun `newline is committed as a char and mapped to enter by caller`() {
        val actions = ImeTextDiff.diff("\u200B", "\u200B\n")
        assertEquals(listOf(ImeKeyAction.Commit('\n')), actions)
        // 发送层由 HidUsageMapper 负责：\n → KEY_ENTER
        assertEquals(0x28, com.pocketkeyboard.app.hid.HidUsageMapper.usageForChar('\n')?.usage)
    }

    @Test
    fun `emoji surrogate pair commits both code units`() {
        // 😀 = U+1F600（代理对 2 个 char）：差分原样提交，映射层再决定丢弃
        val emoji = String(Character.toChars(0x1F600))
        val actions = ImeTextDiff.diff("\u200B", "\u200B$emoji")
        assertEquals(
            listOf(ImeKeyAction.Commit(emoji[0]), ImeKeyAction.Commit(emoji[1])),
            actions,
        )
        // 两个代理单元都不可映射 → 调用方丢弃（中文 / Emoji 的边界处理）
        assertTrue(emoji.none { com.pocketkeyboard.app.hid.HidUsageMapper.usageForChar(it) != null })
    }

    @Test
    fun `chinese candidate commit yields chinese chars that are unmappable`() {
        // 组词结束后候选上屏：文本从哨兵变成「哨兵 + 你好」
        val actions = ImeTextDiff.diff("\u200B", "\u200B你好")
        assertEquals(
            listOf(ImeKeyAction.Commit('你'), ImeKeyAction.Commit('好')),
            actions,
        )
        assertTrue(
            "中文字符没有 HID usage，应由调用方丢弃",
            "你好".none { com.pocketkeyboard.app.hid.HidUsageMapper.usageForChar(it) != null },
        )
    }

    @Test
    fun `common prefix length ignores nothing after first difference`() {
        assertEquals(0, ImeTextDiff.commonPrefixLength("a", "b"))
        assertEquals(2, ImeTextDiff.commonPrefixLength("abc", "abd"))
        assertEquals(3, ImeTextDiff.commonPrefixLength("abc", "abc"))
        assertEquals(1, ImeTextDiff.commonPrefixLength("\u200Bx", "\u200By"))
        assertEquals(0, ImeTextDiff.commonPrefixLength("", "abc"))
    }
}
