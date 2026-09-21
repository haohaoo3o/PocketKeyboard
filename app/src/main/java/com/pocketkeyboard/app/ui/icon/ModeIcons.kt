package com.pocketkeyboard.app.ui.icon

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 底部模式切换栏图标：纯代码矢量（无位图资源），白色描边由 Icon 着色。
 *
 * 路径数据来自 Material Symbols 的 SVG path 字符串；Compose 1.7 起
 * `addPathNodes(String)` 已不再是公开 API，因此这里先用公开的
 * [PathParser.pathStringToNodes] 解析成 [PathNode] 列表，再在 `path { }`
 * 的 PathBuilder 作用域内逐个回放。
 */
object ModeIcons {

    /** 配对（蓝牙符号）。 */
    val Pairing: ImageVector by lazy {
        buildIcon(
            name = "mode_pairing",
            pathData = "M17.71,7.71 L12,2 h-1 v7.59 L6.41,5 L5,6.41 L10.59,12 L5,17.59 " +
                "L6.41,19 L11,14.41 V22 h1 l5.71,-5.71 L13.41,12 L17.71,7.71 Z " +
                "M13,5.83 l1.88,1.88 L13,9.59 V5.83 Z " +
                "M14.88,16.29 L13,18.17 v-3.76 l1.88,1.88 Z",
        )
    }

    /** 键盘。 */
    val Keyboard: ImageVector by lazy {
        buildIcon(
            name = "mode_keyboard",
            pathData = "M20,5 H4 C2.9,5 2,5.9 2,7 v10 c0,1.1 0.9,2 2,2 h16 c1.1,0 2,-1.1 2,-2 V7 " +
                "C22,5.9 21.1,5 20,5 Z " +
                "M11,8 h2 v2 h-2 V8 Z M11,11 h2 v2 h-2 V11 Z M8,8 h2 v2 H8 V8 Z " +
                "M8,11 h2 v2 H8 V11 Z M7,13 H5 v-2 h2 V13 Z M7,10 H5 V8 h2 V10 Z " +
                "M16.5,15 H7.5 C7.22,15 7,14.78 7,14.5 S7.22,14 7.5,14 h9 c0.28,0 0.5,0.22 0.5,0.5 " +
                "S16.78,15 16.5,15 Z " +
                "M13,13 h-2 v-2 h2 V13 Z M16,11 h2 v2 h-2 V11 Z M16,8 h2 v2 h-2 V8 Z",
        )
    }

    /** 触控板（鼠标）。 */
    val Trackpad: ImageVector by lazy {
        buildIcon(
            name = "mode_trackpad",
            pathData = "M13,1.07 V9 h7 c0,-4.08 -3.05,-7.44 -7,-7.93 Z " +
                "M4,15 c0,4.96 4.04,9 9,9 s9,-4.04 9,-9 v-4 H4 V15 Z " +
                "M11,1.07 C7.05,2.44 4,6.08 4,10 h7 V1.07 Z",
        )
    }

    private fun buildIcon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
            .addSvgPath(pathData = pathData, fill = SolidColor(Color.White))
            .build()
}

/**
 * 用 SVG path 字符串给 ImageVector 追加一条填充路径。
 *
 * `PathBuilder` 在 Compose 1.7 里是 internal 类型，无法在函数签名里显式声明，
 * 但 `path { }` 的尾随 lambda 会把 PathBuilder 作为隐式接收者传进来，
 * 因此在 lambda 内部（含嵌套的非接收者 lambda）依然可以直接调用
 * moveTo / lineTo / curveTo 等命令。
 */
private fun ImageVector.Builder.addSvgPath(
    pathData: String,
    fill: Brush,
): ImageVector.Builder = path(fill = fill) {
    PathParser().pathStringToNodes(pathData).forEach { node ->
        when (node) {
            is PathNode.MoveTo -> moveTo(node.x, node.y)
            is PathNode.RelativeMoveTo -> moveToRelative(node.dx, node.dy)
            is PathNode.LineTo -> lineTo(node.x, node.y)
            is PathNode.RelativeLineTo -> lineToRelative(node.dx, node.dy)
            is PathNode.HorizontalTo -> horizontalLineTo(node.x)
            is PathNode.RelativeHorizontalTo -> horizontalLineToRelative(node.dx)
            is PathNode.VerticalTo -> verticalLineTo(node.y)
            is PathNode.RelativeVerticalTo -> verticalLineToRelative(node.dy)
            is PathNode.CurveTo ->
                curveTo(node.x1, node.y1, node.x2, node.y2, node.x3, node.y3)
            is PathNode.RelativeCurveTo ->
                curveToRelative(node.dx1, node.dy1, node.dx2, node.dy2, node.dx3, node.dy3)
            is PathNode.ReflectiveCurveTo ->
                reflectiveCurveTo(node.x1, node.y1, node.x2, node.y2)
            is PathNode.RelativeReflectiveCurveTo ->
                reflectiveCurveToRelative(node.dx1, node.dy1, node.dx2, node.dy2)
            is PathNode.QuadTo -> quadTo(node.x1, node.y1, node.x2, node.y2)
            is PathNode.RelativeQuadTo ->
                quadToRelative(node.dx1, node.dy1, node.dx2, node.dy2)
            is PathNode.ReflectiveQuadTo -> reflectiveQuadTo(node.x, node.y)
            is PathNode.RelativeReflectiveQuadTo ->
                reflectiveQuadToRelative(node.dx, node.dy)
            is PathNode.ArcTo ->
                arcTo(
                    node.horizontalEllipseRadius,
                    node.verticalEllipseRadius,
                    node.theta,
                    node.isMoreThanHalf,
                    node.isPositiveArc,
                    node.arcStartX,
                    node.arcStartY,
                )
            is PathNode.RelativeArcTo ->
                arcToRelative(
                    node.horizontalEllipseRadius,
                    node.verticalEllipseRadius,
                    node.theta,
                    node.isMoreThanHalf,
                    node.isPositiveArc,
                    node.arcStartDx,
                    node.arcStartDy,
                )
            PathNode.Close -> close()
        }
    }
}
