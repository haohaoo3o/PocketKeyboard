package com.pocketkeyboard.app.keyboard

/**
 * 竖屏融合页「系统输入法 → HID」转发的文本差分（纯 Kotlin，无 Compose / Android 依赖，
 * 可直接 JVM 单测）。
 *
 * 目录：app/src/main/java/com/pocketkeyboard/app/keyboard/
 *
 * ## 为什么需要差分而不是逐字符回调
 *
 * 融合页的输入区是一个不可见的 `BasicTextField`：用户点它唤起**系统输入法**，
 * 键入内容先进入这个文本框，再由这里把「文本发生了什么变化」翻译成 HID 按键
 * 发给被控设备（复用 `HidUsageMapper` 的字符 → usage 映射）。InputConnection 的
 * `commitText` / `deleteSurroundingText` 在 Compose 侧统一表现为**文本变更**，
 * 因此只要对「上一次已处理的文本」与「当前文本」做一次差分，就能同时得到：
 *
 * - 追加的字符（正常键入、中文候选上屏、表情）→ 逐字符 [ImeKeyAction.Commit]；
 * - 缩短的部分（退格、选中删除）→ [ImeKeyAction.Backspace]（按字符数补发退格）。
 *
 * ## 与输入法组词（composition）的关系
 *
 * 中文拼音这类「组词中」的文本（`TextFieldValue.composition != null`）**不在这里处理**：
 * 调用方（`FusedImeInputArea`）在组词期间只跟手更新文本、不转发，组词结束
 * （composition 归 null）后再拿「组词开始前已处理的文本」与当前文本做差分。
 * 这样拼音中间态（n → ni → nihao）不会被打给对端，候选上屏的中文汉字则因为
 * `HidUsageMapper.usageForChar` 无法映射而被调用方丢弃（见汇报里的边界说明）。
 */

/** 一次文本变更推导出的按键动作（纯数据）。 */
internal sealed interface ImeKeyAction {

    /** 提交的字符：逐字符查 `HidUsageMapper` 发送；`'\n'` 即回车。 */
    data class Commit(val char: Char) : ImeKeyAction

    /** 文本缩短了 [count] 个字符：补发 [count] 次退格 usage。 */
    data class Backspace(val count: Int) : ImeKeyAction
}

/** 文本差分（纯函数对象）。 */
internal object ImeTextDiff {

    /** 两个字符串的公共前缀长度（按 UTF-16 char 计）。 */
    fun commonPrefixLength(a: String, b: String): Int {
        val max = minOf(a.length, b.length)
        var index = 0
        while (index < max && a[index] == b[index]) index++
        return index
    }

    /**
     * 从 [previous]（上一次已处理的文本）到 [current]（当前文本）的按键动作序列。
     *
     * 规则：
     * - 完全相同 → 空序列；
     * - 公共前缀之后：[previous] 多出来的部分算删除（退格），[current] 多出来的部分
     *   算提交（逐字符），删除排在提交之前（与输入法「先删后填」的真实顺序一致，
     *   例如自动改正把 teh 换成 the 时也是先删后提交）。
     */
    fun diff(previous: String, current: String): List<ImeKeyAction> {
        if (previous == current) return emptyList()
        val common = commonPrefixLength(previous, current)
        val removed = previous.length - common
        val appended = current.substring(common)
        return buildList {
            if (removed > 0) add(ImeKeyAction.Backspace(removed))
            appended.forEach { char -> add(ImeKeyAction.Commit(char)) }
        }
    }
}
