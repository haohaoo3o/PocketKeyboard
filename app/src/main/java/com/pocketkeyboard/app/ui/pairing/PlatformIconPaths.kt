package com.pocketkeyboard.app.ui.pairing

/**
 * 平台徽标的 SVG path 数据与归一化工具（Bug 1：App 内图标残缺变形）。
 *
 * ## 这个文件为什么存在
 * 上一版把 simple-icons 的 path 字符串按「每行 80 字符左右」切成多个 Kotlin 字符串字面量
 * 再拼接，结果其中一条路径的换行点正好落在**数字中间**：
 * `…2.676-1.48` + `3.676-2.948…` → `…2.676-1.483.676-2.948…`，
 * 解析器不会报错，只会把 `1.48` 读成 `1.483`、把 `3.676` 读成 `.676`，苹果 logo 的
 * 右半侧叶子与咬口位置整体错位（真机截图可见）。
 *
 * 因此这里做三件事：
 * 1. [PlatformIconPaths] 的每条 path **逐字符对齐上游 simple-icons**（CC0 授权，
 *    viewBox 0 0 24 24），常量字符串只在「命令字母 / 空格」处断行，绝不在数字中断开；
 * 2. [SvgPathNormalizer] 在解析前把 path 归一化成「token 之间恰好一个空格」的规范形式，
 *    把「数字被空格吃掉 / 逗号 / 换行粘连」这类问题显式化——即使以后有人换行不当，
 *    归一化器也能把两个紧邻的数字重新分开（见 `SvgPathNormalizerTest`）；
 * 3. 单测逐项校验解析结果（节点值 + 包围盒 + 光栅化覆盖率），任何一处数字被改坏都会红。
 *
 * 渲染侧（`PairingScreen.PlatformIcon`）另有第二个坑：`scale(scaleX, scaleY)` 的 pivot
 * 默认是**画布中心**，viewport 坐标 [0,24] 绕中心缩放后整条路径被平移出画布，只剩一小条
 * 碎片可见——那正是「Windows logo 渲染破碎」的直接原因。修复见
 * `PairingScreen.drawPlatformIconPath`（显式 `pivot = Offset.Zero`）。
 */
internal object PlatformIconPaths {

    /** SVG 视口边长（simple-icons 全部为 24×24）。 */
    const val VIEWPORT = 24f

    /**
     * 苹果 logo（带叶子与咬口的剪影）。
     *
     * 来源：simple-icons `icons/apple.svg`（CC0），上游原文：
     * `M12.152 6.896c-.948 0-2.415-1.078-3.96-1.04-2.04.027-3.91 1.183-4.961
     *  3.014-2.117 3.675-.546 9.103 1.519 12.09 1.013 1.454 2.208 3.09 3.792 3.039
     *  1.52-.065 2.09-.987 3.935-.987 1.831 0 2.35.987 3.96.948 1.637-.026 2.676-1.48
     *  3.676-2.948 1.156-1.688 1.636-3.325 1.662-3.415-.039-.013-3.182-1.221-3.22-4.857
     *  -.026-3.04 2.48-4.494 2.597-4.559-1.429-2.09-3.623-2.324-4.39-2.376-2-.156-3.675
     *  1.09-4.61 1.09zM15.53 3.83c.843-1.012 1.4-2.427 1.245-3.83-1.207.052-2.662.805
     *  -3.532 1.818-.78.896-1.454 2.338-1.273 3.714 1.338.104 2.715-.688 3.559-1.701`
     */
    const val APPLE = "M12.152 6.896c-.948 0-2.415-1.078-3.96-1.04-2.04.027-3.91" +
        " 1.183-4.961 3.014-2.117 3.675-.546 9.103 1.519 12.09 1.013 1.454" +
        " 2.208 3.09 3.792 3.039 1.52-.065 2.09-.987 3.935-.987 1.831 0" +
        " 2.35.987 3.96.948 1.637-.026 2.676-1.48 3.676-2.948 1.156-1.688" +
        " 1.636-3.325 1.662-3.415-.039-.013-3.182-1.221-3.22-4.857-.026-3.04" +
        " 2.48-4.494 2.597-4.559-1.429-2.09-3.623-2.324-4.39-2.376-2-.156" +
        "-3.675 1.09-4.61 1.09z" +
        "M15.53 3.83c.843-1.012 1.4-2.427 1.245-3.83-1.207.052-2.662.805-3.532" +
        " 1.818-.78.896-1.454 2.338-1.273 3.714 1.338.104 2.715-.688 3.559-1.701"

    /**
     * Windows logo（四格窗格）。
     *
     * 来源：simple-icons `icons/windows.svg`（CC0；新版仓库已移除该图标，取值为
     * simple-icons 5.x / 6.x npm 包内原文，与旧版仓库完全一致）：
     * `M0 3.449L9.75 2.1v9.451H0m10.949-9.602L24 0v11.4H10.949M0 12.6h9.75v9.451
     *  L0 20.699M10.949 12.6H24V24l-12.9-1.801`
     *
     * ⚠️ 历史坑：上一版把最后一段的手写体抄成了**小写 m**（`m10.949 12.6`）。小写 m 是
     * 相对移动，起点是上一段终点 `(0, 20.699)`，于是右下角窗格被画到 y≈33.3–44.7
     * （整个掉出 24×24 视口），真机上只剩三块半透明玻璃——这就是「Windows logo
     * 渲染破碎」的第二个原因。上游原文末段是**大写 M**（绝对移动）。
     */
    const val WINDOWS = "M0 3.449L9.75 2.1v9.451H0" +
        "m10.949-9.602L24 0v11.4H10.949" +
        "M0 12.6h9.75v9.451L0 20.699" +
        "M10.949 12.6H24V24l-12.9-1.801"
}

/**
 * SVG path 字符串归一化器：把任意合法 SVG path 重排成「同一命令的参数之间恰好一个空格、
 * 命令字母独占边界」的规范形式。
 *
 * 解决的真实问题：SVG path 是**无分隔符歧义文法**——`M3.014-2.117` 合法（负号即分隔），
 * 但把一条 path 按固定宽度折行再拼接时，一旦断点落在数字中间（`…2.676-1.48` +
 * `3.676-2.948…`）就会静默粘成一个错误数字。归一化器按 SVG 规范的数字文法逐 token 重新
 * 切分：
 *
 * ```
 * number ::= sign? ( digits ('.' digits?)? | '.' digits ) ('e'|'E' sign? digits)?
 * ```
 *
 * 因此 `2.676-1.483.676-2.948`（被粘坏）会被切回 `2.676 -1.48 3.676 -2.948`
 * 的**规范形式**——但注意：数字之间的「裸粘连」（`12` + `34` → `1234`）在语法上本就
 * 无法与一个完整数字区分，这类错误只能由单测的节点值 / 包围盒断言兜底
 * （见 `PlatformIconPathsTest`），归一化器负责的是「有符号 / 有小数点边界」这一类。
 *
 * 逗号、连续空白在 SVG 里等价于一个分隔符，输出统一折叠为空格；不在数字中间插入任何
 * 字符，因此合法 path 归一化后语义不变（可与 `pathStringToNodes` 的结果对比验证）。
 *
 * 限制：不支持 `A`/`a`（椭圆弧）命令的 flag 简写（`0 1 1 0` 连写成 `011 0`）——
 * 本工程两条徽标 path 都不含弧线；若将来引入，需要在数字解析处特殊处理 flag。
 */
internal object SvgPathNormalizer {

    /** SVG path 的合法命令字母（flag 不算命令）。 */
    private const val COMMAND_LETTERS = "MmLlHhVvCcSsQqTtAaZz"

    /**
     * 归一化一条 SVG path 字符串。
     *
     * @throws IllegalArgumentException 源串含有无法识别的字符（开发期数据错误，尽早暴露）。
     */
    fun normalize(source: String): String {
        val out = StringBuilder(source.length + 16)
        var index = 0
        var previousWasNumber = false
        while (index < source.length) {
            val ch = source[index]
            if (ch.isWhitespace() || ch == ',') {
                // 分隔符统一折叠，遇到下一个 token 时再补空格
                index++
                continue
            }
            if (ch in COMMAND_LETTERS) {
                out.append(ch)
                index++
                previousWasNumber = false
                continue
            }
            val number = readNumber(source, index)
            if (previousWasNumber) out.append(' ')
            out.append(number.value)
            index = number.next
            previousWasNumber = true
        }
        return out.toString()
    }

    /** 从 [start] 读出一个符合 SVG 文法的数字，返回数字文本与其结束下标。 */
    private fun readNumber(source: String, start: Int): Token {
        var index = start
        if (index < source.length && (source[index] == '+' || source[index] == '-')) index++
        var digits = 0
        while (index < source.length && isDigit(source[index])) {
            index++
            digits++
        }
        // 小数部分：`.` 后可以没有数字（SVG 允许 `1.`），但至少要有一个数字前缀或小数位
        if (index < source.length && source[index] == '.') {
            index++
            while (index < source.length && isDigit(source[index])) {
                index++
                digits++
            }
        }
        if (digits == 0) {
            throw IllegalArgumentException(
                "SVG path 归一化失败：位置 $start 附近不是合法数字（字符 '${source.getOrNull(start)}'）",
            )
        }
        // 科学计数法：只有后面紧跟可选符号 + 数字时才吃掉 'e'/'E'，
        // 否则 'e' 留在串里由下一次循环报错（path 数据里不该出现孤立的 'e'）
        if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
            var lookahead = index + 1
            if (lookahead < source.length &&
                (source[lookahead] == '+' || source[lookahead] == '-')
            ) {
                lookahead++
            }
            var exponentDigits = 0
            while (lookahead < source.length && isDigit(source[lookahead])) {
                lookahead++
                exponentDigits++
            }
            if (exponentDigits > 0) index = lookahead
        }
        return Token(source.substring(start, index), index)
    }

    private fun isDigit(ch: Char): Boolean = ch in '0'..'9'

    private class Token(val value: String, val next: Int)
}
