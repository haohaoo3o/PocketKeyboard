package com.pocketkeyboard.app.gesture

import androidx.compose.ui.input.pointer.PointerInputChange

/** 单个指尖在屏幕上的位置（用自带类型而非 Compose 的 Offset，保证可在纯 JVM 下单测）。 */
data class FingerPoint(val x: Float, val y: Float)

/**
 * 五指几何快照。
 *
 * @param centroidX 5 个指尖 x 的算术平均（质心横坐标）
 * @param centroidY 5 个指尖 y 的算术平均（质心纵坐标）
 * @param meanSpacing 指尖平均间距：5 个指尖两两之间共 C(5,2)=10 段距离的算术平均。
 *   相比「最大间距」或「外接矩形对角线」，平均值对某一根手指的抖动更不敏感。
 */
data class FiveFingerGeometry(
    val centroidX: Float,
    val centroidY: Float,
    val meanSpacing: Float,
)

/** 横滑方向：质心向右移动为 [RIGHT]，向左为 [LEFT]。 */
enum class SwipeDirection { LEFT, RIGHT }

/** 一次五指挥势的判定结果。 */
sealed interface GestureVerdict {

    /** 尚未达到任何阈值，继续跟踪。 */
    data object None : GestureVerdict

    /** 五指收缩：间距缩小超过 [GestureConstants.PINCH_SHRINK_RATIO]。 */
    data object Pinch : GestureVerdict

    /** 五指张开：间距放大超过 [GestureConstants.SPREAD_GROW_RATIO]。 */
    data object Spread : GestureVerdict

    /** 五指整体横滑：质心水平位移超过 [GestureConstants.SWIPE_DISTANCE]。 */
    data class Swipe(val direction: SwipeDirection) : GestureVerdict
}

/** 把当前按下的指针转换成指尖坐标列表。 */
fun List<PointerInputChange>.toFingerPoints(): List<FingerPoint> =
    map { FingerPoint(it.position.x, it.position.y) }

/**
 * 计算五指几何快照。
 *
 * @param points 至少要包含 [GestureConstants.REQUIRED_FINGER_COUNT] 个指尖，否则返回 null。
 *    **多于 5 个也接受**（手心 / 掌根贴到屏幕时系统会多报一根指针）：真机实测一根额外的
 *    掌根接触在 5 指挥势里很常见，若按「必须恰好 5 根」判 null，整个手势会被静默丢弃
 *   （上一轮 pinch / spread 「不触发」的疑点之一）。超过 5 根时按全部指尖计算，
 *   收缩 / 张开是**比例**判定、横滑比的是**质心位移**，多一根掌根只带来很小的偏差，
 *   不至于把合法手势判死。
 */
fun fiveFingerGeometry(points: List<FingerPoint>): FiveFingerGeometry? {
    if (points.size < GestureConstants.REQUIRED_FINGER_COUNT) return null
    var sumX = 0f
    var sumY = 0f
    points.forEach {
        sumX += it.x
        sumY += it.y
    }
    val n = points.size.toFloat()
    val centroidX = sumX / n
    val centroidY = sumY / n

    var spacingSum = 0f
    var pairCount = 0
    for (i in points.indices) {
        for (j in i + 1 until points.size) {
            spacingSum += distance(points[i], points[j])
            pairCount++
        }
    }
    // 理论上 pairCount == C(5,2) == 10；这里按实际配对数取平均，避免除零
    val meanSpacing = if (pairCount == 0) 0f else spacingSum / pairCount
    return FiveFingerGeometry(centroidX, centroidY, meanSpacing)
}

/**
 * 依据初始几何与当前几何判定五指挥势。
 *
 * 优先级：收缩 / 张开 高于 横滑。原因是捏合与张开几乎总会伴随质心移动，
 * 若横滑优先则一次「五指张开」会被误判成设备切换。
 *
 * @param initial 凑满五指那一刻的几何快照（判定基准）
 * @param current 当前几何快照
 * @param swipeThresholdPx 横滑阈值（像素），由 [GestureConstants.SWIPE_DISTANCE] 换算而来
 */
fun classifyFiveFingerGesture(
    initial: FiveFingerGeometry,
    current: FiveFingerGeometry,
    swipeThresholdPx: Float,
): GestureVerdict {
    val baseline = initial.meanSpacing
    if (baseline > GestureConstants.GEOMETRY_EPSILON) {
        val ratio = current.meanSpacing / baseline
        if (ratio <= 1f - GestureConstants.PINCH_SHRINK_RATIO) return GestureVerdict.Pinch
        if (ratio >= 1f + GestureConstants.SPREAD_GROW_RATIO) return GestureVerdict.Spread
    }
    val dx = current.centroidX - initial.centroidX
    if (dx >= swipeThresholdPx) return GestureVerdict.Swipe(SwipeDirection.RIGHT)
    if (dx <= -swipeThresholdPx) return GestureVerdict.Swipe(SwipeDirection.LEFT)
    return GestureVerdict.None
}

/** 两点间欧氏距离。 */
internal fun distance(a: FingerPoint, b: FingerPoint): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return kotlin.math.sqrt(dx * dx + dy * dy)
}
