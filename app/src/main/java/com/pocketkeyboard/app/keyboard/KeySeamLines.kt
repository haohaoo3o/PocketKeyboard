package com.pocketkeyboard.app.keyboard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

/**
 * 键帽之间的「几乎无缝但可辨」细分隔线（需求 5：渐变细线，白色低透明度，从一端淡出）。
 *
 * 目录：app/src/main/java/com/pocketkeyboard/app/keyboard/
 *
 * ## 观感规格
 *
 * - 线宽 1dp、纯白、最高透明度 [SEAM_LINE_ALPHA]（10%，落在需求给的 8–12% 区间）；
 * - 垂直分隔线（左右键之间）：**自上而下淡出**；水平分隔线（上下行之间）：**自左向右淡出**；
 *   末端透明度趋近 0，因此永远不会出现「生硬实线」的网格感，远看仍是一整块无缝键盘；
 * - 画在键帽格子的右缘 / 下缘、向格子内缩半个线宽，保证屏幕最右 / 最下边缘的线
 *   也不会被裁掉一半而显得突兀。
 *
 * ## 为什么画在 graphicsLayer 之外
 *
 * `KeyCap` 按下时整体缩放到 0.94（下陷动画）。分隔线挂在 `graphicsLayer`
 * **之前**的 `drawWithContent` 上（即父节点），画的是格子原始坐标：按下时键帽内容
 * 缩下去、分隔线留在原位，网格不会随下陷动画晃动。
 */
internal val SEAM_LINE_ALPHA = 0.10f

/** 分隔线线宽（1dp，亚像素级细线）。 */
internal val SEAM_LINE_WIDTH = 1.dp

/**
 * 垂直分隔线：画在 [x]（px，线的左缘）、宽 [width]（通常 = 1dp），
 * 纵向铺满 [height]，白色自上而下淡出。
 */
internal fun DrawScope.drawVerticalSeamLine(x: Float, width: Float, height: Float) {
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = SEAM_LINE_ALPHA),
            1f to Color.Transparent,
        ),
        topLeft = Offset(x, 0f),
        size = Size(width, height),
    )
}

/**
 * 水平分隔线：画在 [y]（px，线的上缘）、高 [height]（通常 = 1dp），
 * 横向铺满 [width]，白色自左向右淡出。
 */
internal fun DrawScope.drawHorizontalSeamLine(y: Float, width: Float, height: Float) {
    drawRect(
        brush = Brush.horizontalGradient(
            0f to Color.White.copy(alpha = SEAM_LINE_ALPHA),
            1f to Color.Transparent,
        ),
        topLeft = Offset(0f, y),
        size = Size(width, height),
    )
}

/**
 * 融合页分区之间的分隔线：一条居中的短横线，向两侧淡出（区分「触控板 / 输入区 /
 * 修饰键排」三段，同时不破坏纯黑极简观感）。
 */
internal fun DrawScope.drawCenteredFadingSeamLine(width: Float, height: Float) {
    val thickness = SEAM_LINE_WIDTH.toPx()
    val y = (height - thickness) / 2f
    val inset = width * 0.18f
    drawRect(
        brush = Brush.horizontalGradient(
            0f to Color.Transparent,
            0.5f to Color.White.copy(alpha = SEAM_LINE_ALPHA),
            1f to Color.Transparent,
        ),
        topLeft = Offset(inset, y),
        size = Size(width - inset * 2f, thickness),
    )
}
