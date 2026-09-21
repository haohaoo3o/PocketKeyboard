package com.pocketkeyboard.app.gesture

import com.pocketkeyboard.app.ui.PairedDevice

/**
 * 在已配对设备间循环切换控制目标（五指整体横滑的结果）。
 *
 * 约定：质心向右滑（[SwipeDirection.RIGHT]）切到列表里的下一台设备，向左滑切到上一台；
 * 到达列表末尾时绕回第一台。当前设备不在列表里（或还没有选中任何设备）时取第一台。
 *
 * 这里只做纯计算，不直接写 [com.pocketkeyboard.app.ui.MainViewModel]，
 * 由调用方（MainActivity 的手势回调）负责 `setActiveDevice(next)`，以保持本包与
 * ViewModel 的解耦，也方便在 JVM 单测里覆盖。
 */
fun cycleActiveDevice(
    devices: List<PairedDevice>,
    current: PairedDevice?,
    direction: SwipeDirection,
): PairedDevice? {
    if (devices.isEmpty()) return null
    val currentIndex = devices.indexOfFirst { it.address == current?.address }
    if (currentIndex < 0) return devices.first()
    val step = if (direction == SwipeDirection.RIGHT) 1 else -1
    return devices[nextActiveDeviceIndex(currentIndex, devices.size, step)]
}

/**
 * 循环索引计算：把 `currentIndex + step` 归一化到 `[0, size)`。
 *
 * @param size 设备数量，必须 > 0
 */
fun nextActiveDeviceIndex(currentIndex: Int, size: Int, step: Int): Int {
    require(size > 0) { "设备列表不能为空" }
    if (currentIndex < 0) return 0
    val raw = currentIndex + step
    return ((raw % size) + size) % size
}
