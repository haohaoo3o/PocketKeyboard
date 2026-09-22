package com.pocketkeyboard.app.ui.pairing

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 平台徽标 path 数据与归一化器的单测（Bug 1）。
 *
 * 覆盖三层：
 * 1. **归一化器**（纯 JVM 规则）：[SvgPathNormalizer] 的分词与重排不得改变语义，
 *    且能容错逗号 / 连续空白 / 折行；
 * 2. **数据层**：两条徽标 path 归一化后必须与上游 simple-icons 原文**逐 token 一致**
 *    （[REFERENCE_APPLE] / [REFERENCE_WINDOWS]），包围盒必须落在 24×24 视口内
 *    ——上一版两处数据错误（苹果段落被折行粘成 `1.483.676`、Windows 末段小写 `m`
 *    变成相对移动）都由这里兜住；
 * 3. **渲染层**（Robolectric 原生图形光栅化）：同一段**生产代码**
 *    [drawPlatformIconPath] 在 66×66 画布上的白色覆盖率必须落在合理区间——修复前
 *    （`scale` 默认 pivot = 画布中心 + 上述两处数据错误）Windows logo 实测只有
 *    约 4.8%，修复后约 80.7%。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlatformIconPathsTest {

    // ------------------------------------------------------------ 归一化器

    @Test
    fun `normalizer canonicalizes every number with explicit separators`() {
        // 折行 + 逗号 + 连续空白都不该改变 token 序列，只是统一成「命令 + 单空格分隔」
        assertEquals(
            "2.676 -1.48 3.676 -2.948",
            SvgPathNormalizer.normalize("2.676-1.48 3.676-2.948"),
        )
        assertEquals(
            "M3.014 -2.117",
            SvgPathNormalizer.normalize("M3.014-2.117"),
        )
    }

    @Test
    fun `normalizer collapses commas and whitespace runs`() {
        assertEquals(
            "M12.152 6.896c-.948 0 -2.415 -1.078",
            SvgPathNormalizer.normalize("M12.152, 6.896\t\nc -.948  0 -2.415 -1.078"),
        )
    }

    @Test
    fun `normalizer keeps a leading dot decimal as its own number`() {
        // SVG 文法里 "1.5.5" 是两个数字（1.5 与 .5），归一化后必须显式分开
        assertEquals(".5 .5", SvgPathNormalizer.normalize(".5.5"))
    }

    @Test
    fun `normalizer keeps scientific notation together`() {
        // "1.5e-32.5" 按 SVG 文法是 1.5e-32 与 .5 两个数字
        assertEquals("1.5e-32 .5", SvgPathNormalizer.normalize("1.5e-32.5"))
        assertEquals("1.5e-3 2.5", SvgPathNormalizer.normalize("1.5e-3 2.5"))
    }

    @Test
    fun `normalizer is idempotent`() {
        val once = SvgPathNormalizer.normalize(PlatformIconPaths.APPLE)
        assertEquals(once, SvgPathNormalizer.normalize(once))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `normalizer rejects garbage`() {
        SvgPathNormalizer.normalize("M0 3.449@1")
    }

    @Test
    fun `normalizer does not change parse results of a valid path`() {
        // 归一化不得改变语义：规范化前后的节点序列必须一致
        assertEquals(
            PathParser().pathStringToNodes(PlatformIconPaths.WINDOWS).readable(),
            PathParser().pathStringToNodes(SvgPathNormalizer.normalize(PlatformIconPaths.WINDOWS))
                .readable(),
        )
    }

    // ------------------------------------------------------------ 数据层（对齐上游）

    @Test
    fun `apple path matches upstream simple-icons token by token`() {
        assertEquals(
            PathParser().pathStringToNodes(REFERENCE_APPLE).readable(),
            PathParser().pathStringToNodes(SvgPathNormalizer.normalize(PlatformIconPaths.APPLE))
                .readable(),
        )
    }

    @Test
    fun `windows path matches upstream simple-icons token by token`() {
        assertEquals(
            PathParser().pathStringToNodes(REFERENCE_WINDOWS).readable(),
            PathParser().pathStringToNodes(SvgPathNormalizer.normalize(PlatformIconPaths.WINDOWS))
                .readable(),
        )
    }

    @Test
    fun `apple path stays inside the 24x24 viewport`() {
        val bounds = applePath().getBounds()
        // Compose 的 getBounds 含贝塞尔控制点，允许 0.2 单位的保守外扩
        assertTrue("上/左界越界: $bounds", bounds.left >= -0.2f && bounds.top >= -0.2f)
        assertTrue("下/右界越界: $bounds", bounds.right <= 24.2f && bounds.bottom <= 24.2f)
        assertTrue("图形过小: $bounds", bounds.width > 18f && bounds.height > 20f)
    }

    @Test
    fun `apple path relative curve keeps upstream numbers`() {
        val curves = applePathNodes().filterIsInstance<PathNode.RelativeCurveTo>()
        // 上一版被折行粘连改坏的那一段（上游: dx1 1.637 dy1 -0.026 dx2 2.676 dy2 -1.48 dx3 3.676 dy3 -2.948）
        val leafTip = curves[6]
        assertEquals(1.637f, leafTip.dx1, TOLERANCE)
        assertEquals(-0.026f, leafTip.dy1, TOLERANCE)
        assertEquals(2.676f, leafTip.dx2, TOLERANCE)
        assertEquals(-1.48f, leafTip.dy2, TOLERANCE)
        assertEquals(3.676f, leafTip.dx3, TOLERANCE)
        assertEquals(-2.948f, leafTip.dy3, TOLERANCE)
    }

    @Test
    fun `windows path draws four panes with the right cases`() {
        val nodes = windowsPathNodes()
        // 子路径 1 / 3 是绝对 MoveTo，2 / 4 是相对 / 绝对混排——末段必须是**大写 M**
        val moves = nodes.filterIsInstance<PathNode.MoveTo>()
        val relativeMoves = nodes.filterIsInstance<PathNode.RelativeMoveTo>()
        assertEquals(3, moves.size)
        assertEquals(1, relativeMoves.size)
        // 末段（右下窗格）必须是绝对移动到 (10.949, 12.6)，而不是从 (0, 20.699) 相对下移
        assertEquals(10.949f, moves[2].x, TOLERANCE)
        assertEquals(12.6f, moves[2].y, TOLERANCE)
    }

    @Test
    fun `windows path stays inside the 24x24 viewport`() {
        val bounds = windowsPath().getBounds()
        assertTrue("左/上越界: $bounds", bounds.left >= -0.001f && bounds.top >= -0.001f)
        assertTrue("右/下越界: $bounds", bounds.right <= 24.001f && bounds.bottom <= 24.001f)
        assertTrue("图形过小: $bounds", bounds.width > 22f && bounds.height > 22f)
    }

    @Test
    fun `a lowercase relative move in the last pane would leave the viewport`() {
        // 数据护栏：复现上一版的抄写错误（末段小写 m），右下角窗格会被相对移动到
        // y≈33.3，整个掉出视口——包围盒断言必须因此变红
        val broken = PlatformIconPaths.WINDOWS.replace(
            "M10.949 12.6H24V24",
            "m10.949 12.6H24V24",
        )
        val bounds = PathParser().parsePathString(SvgPathNormalizer.normalize(broken))
            .toPath(Path())
            .getBounds()
        assertTrue("相对移动后右下窗格应掉出视口（实测下界≈33.3），实际 $bounds", bounds.bottom > 24.5f)
    }

    // ------------------------------------------------------------ 渲染层

    @Test
    fun `platform icon fills most of the canvas`() {
        // 66×66 = 22dp @ 3x，真机设备行图标的实际像素尺寸
        val coverage = whiteRatio(rasterize { drawPlatformIconPath(windowsPath()) })
        // 四格窗格铺满视口大部分面积；实测 0.807（修复前同画布实测 0.048）
        assertTrue(
            "修复后覆盖率应铺满四格 logo（实测约 0.81），实际 $coverage",
            coverage in 0.65f..0.95f,
        )
    }

    @Test
    fun `centered pivot would break the icon into a fragment`() {
        // 回归护栏：复现修复前的错误写法（scale 的 pivot 缺省 = 画布中心），
        // 覆盖率必须远低于修复后的实现——这条红了说明又回到了绕中心缩放
        val path = windowsPath()
        val coverage = whiteRatio(
            rasterize {
                // 故意不传 pivot：默认值即画布中心
                scale(CANVAS / PlatformIconPaths.VIEWPORT, CANVAS / PlatformIconPaths.VIEWPORT) {
                    drawPath(path = path, color = Color.White)
                }
            },
        )
        assertTrue("绕中心缩放的覆盖率 $coverage 应低于 0.2", coverage < 0.2f)
    }

    @Test
    fun `apple logo rasterizes with a visible silhouette`() {
        val coverage = whiteRatio(rasterize { drawPlatformIconPath(applePath()) })
        // 苹果 logo 在 24×24 视口内占比低于四格 Windows logo，但仍应清晰可见
        assertTrue("苹果 logo 覆盖率 $coverage 过低", coverage in 0.20f..0.75f)
    }

    @Test
    fun `corrupted apple path would rasterize a different silhouette`() {
        // 数据护栏：把上游数据按上一版的方式粘坏（1.48 + 3.676 → 1.483.676），
        // 渲染出的覆盖率必须与正确数据不同
        val glued = PathParser()
            .parsePathString(
                SvgPathNormalizer.normalize(
                    PlatformIconPaths.APPLE.replace("2.676-1.48 3.676-2.948", "2.676-1.483.676-2.948"),
                ),
            )
            .toPath(Path())
        val correctRatio = whiteRatio(rasterize { drawPlatformIconPath(applePath()) })
        val brokenRatio = whiteRatio(rasterize { drawPlatformIconPath(glued) })
        assertTrue(
            "两条数据的渲染覆盖率不应相同: correct=$correctRatio broken=$brokenRatio",
            abs(correctRatio - brokenRatio) > 0.01f,
        )
    }

    // ------------------------------------------------------------ 工具

    /** 用生产代码解析苹果 path（含归一化）。 */
    private fun applePath(): Path =
        PathParser().parsePathString(SvgPathNormalizer.normalize(PlatformIconPaths.APPLE))
            .toPath(Path())

    /** 用生产代码解析 Windows path（含归一化）。 */
    private fun windowsPath(): Path =
        PathParser().parsePathString(SvgPathNormalizer.normalize(PlatformIconPaths.WINDOWS))
            .toPath(Path())

    private fun applePathNodes(): List<PathNode> =
        PathParser().pathStringToNodes(SvgPathNormalizer.normalize(PlatformIconPaths.APPLE))

    private fun windowsPathNodes(): List<PathNode> =
        PathParser().pathStringToNodes(SvgPathNormalizer.normalize(PlatformIconPaths.WINDOWS))

    /** PathNode 的可读形式（PathNode 没有稳定的 toString，逐字段拼）。 */
    private fun List<PathNode>.readable(): List<String> = map { node ->
        when (node) {
            is PathNode.MoveTo -> "M${node.x},${node.y}"
            is PathNode.RelativeMoveTo -> "m${node.dx},${node.dy}"
            is PathNode.LineTo -> "L${node.x},${node.y}"
            is PathNode.RelativeLineTo -> "l${node.dx},${node.dy}"
            is PathNode.HorizontalTo -> "H${node.x}"
            is PathNode.RelativeHorizontalTo -> "h${node.dx}"
            is PathNode.VerticalTo -> "V${node.y}"
            is PathNode.RelativeVerticalTo -> "v${node.dy}"
            is PathNode.CurveTo -> "C${node.x1},${node.y1},${node.x2},${node.y2},${node.x3},${node.y3}"
            is PathNode.RelativeCurveTo ->
                "c${node.dx1},${node.dy1},${node.dx2},${node.dy2},${node.dx3},${node.dy3}"
            is PathNode.ReflectiveCurveTo -> "S${node.x1},${node.y1},${node.x2},${node.y2}"
            is PathNode.RelativeReflectiveCurveTo -> "s${node.dx1},${node.dy1},${node.dx2},${node.dy2}"
            is PathNode.QuadTo -> "Q${node.x1},${node.y1},${node.x2},${node.y2}"
            is PathNode.RelativeQuadTo -> "q${node.dx1},${node.dy1},${node.dx2},${node.dy2}"
            is PathNode.ReflectiveQuadTo -> "T${node.x},${node.y}"
            is PathNode.RelativeReflectiveQuadTo -> "t${node.dx},${node.dy}"
            is PathNode.ArcTo -> "A${node.horizontalEllipseRadius},${node.verticalEllipseRadius}"
            is PathNode.RelativeArcTo -> "a${node.horizontalEllipseRadius},${node.verticalEllipseRadius}"
            PathNode.Close -> "Z"
        }
    }

    /** 在 [CANVAS]×[CANVAS] 的画布上执行 [block]，返回可读像素的 ImageBitmap。 */
    private fun rasterize(block: DrawScope.() -> Unit): ImageBitmap {
        val bitmap = ImageBitmap(CANVAS, CANVAS)
        Canvas(bitmap).let { canvas ->
            CanvasDrawScope().draw(
                density = Density(3f),
                layoutDirection = LayoutDirection.Ltr,
                canvas = canvas,
                size = Size(CANVAS.toFloat(), CANVAS.toFloat()),
            ) {
                block()
            }
        }
        return bitmap
    }

    /** 白色像素占比（图标是白色填充，画布默认透明）。 */
    private fun whiteRatio(bitmap: ImageBitmap): Float {
        val map = bitmap.toPixelMap()
        var white = 0
        for (y in 0 until map.height) for (x in 0 until map.width) {
            val pixel = map[x, y]
            if (pixel.red > 0.5f && pixel.green > 0.5f && pixel.blue > 0.5f) white++
        }
        return white.toFloat() / (map.width * map.height)
    }

    private fun abs(value: Float): Float = if (value < 0) -value else value

    private companion object {
        const val TOLERANCE = 0.001f

        /** 光栅化画布边长（px）：22dp 图标在 3x 密度下的实际尺寸。 */
        const val CANVAS = 66

        /**
         * 上游 simple-icons `icons/apple.svg`（CC0）的 path 原文（单空格分隔形式）。
         *
         * 与 [PlatformIconPaths.APPLE] 逐 token 对比：任何数字被折行粘连 / 抄错都会红。
         */
        const val REFERENCE_APPLE =
            "M12.152 6.896c-.948 0-2.415-1.078-3.96-1.04-2.04.027-3.91 1.183-4.961" +
                " 3.014-2.117 3.675-.546 9.103 1.519 12.09 1.013 1.454 2.208 3.09 3.792" +
                " 3.039 1.52-.065 2.09-.987 3.935-.987 1.831 0 2.35.987 3.96.948 1.637-.026" +
                " 2.676-1.48 3.676-2.948 1.156-1.688 1.636-3.325 1.662-3.415-.039-.013" +
                "-3.182-1.221-3.22-4.857-.026-3.04 2.48-4.494 2.597-4.559-1.429-2.09" +
                "-3.623-2.324-4.39-2.376-2-.156-3.675 1.09-4.61 1.09z" +
                "M15.53 3.83c.843-1.012 1.4-2.427 1.245-3.83-1.207.052-2.662.805-3.532" +
                " 1.818-.78.896-1.454 2.338-1.273 3.714 1.338.104 2.715-.688 3.559-1.701"

        /**
         * 上游 simple-icons `icons/windows.svg`（CC0，取 5.x/6.x npm 包原文）的 path。
         *
         * 末段是**大写 M**（绝对移动）——上一版抄成小写 m 导致右下窗格掉出视口。
         */
        const val REFERENCE_WINDOWS =
            "M0 3.449L9.75 2.1v9.451H0m10.949-9.602L24 0v11.4H10.949M0 12.6h9.75v9.451" +
                "L0 20.699M10.949 12.6H24V24l-12.9-1.801"
    }
}
