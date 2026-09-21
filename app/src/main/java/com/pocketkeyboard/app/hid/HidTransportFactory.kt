package com.pocketkeyboard.app.hid

import android.content.Context
import android.os.Build
import android.bluetooth.BluetoothManager

/**
 * HID 传输实现选择器：能用方案 A 就用方案 A，否则退回方案 B（[NullHidTransport]）。
 *
 * 判定顺序（同步可判的部分）：
 * 1. `bluetoothManager == null` 或拿不到 adapter → 本机无蓝牙 → 方案 B；
 * 2. `Build.VERSION.SDK_INT < P`（API 28）→ 系统没有 HidDevice profile → 方案 B；
 * 3. 其余情况 → [HidDeviceTransport]。
 *
 * 还有一类「运行期才发现的不可用」无法在这里同步判断，例如 ROM 禁用 profile、
 * registerApp 被其他应用占用、蓝牙在启动后被关闭。这些情况由实现内部通过
 * [HidStatusListener.onUnavailable] 上报；调用方若想彻底切换成空实现，
 * 可调用 [fallback] 拿到一个等价的 [NullHidTransport]。
 */
object HidTransportFactory {

    /**
     * 创建传输实现。
     *
     * @param context Application Context（传给系统实现）。
     * @param bluetoothManager 系统蓝牙管理器，可传 null。
     * @param statusListener 状态出口。
     */
    fun create(
        context: Context,
        bluetoothManager: BluetoothManager?,
        statusListener: HidStatusListener,
    ): HidTransport {
        val adapter = bluetoothManager?.adapter
        if (bluetoothManager == null || adapter == null) {
            return NullHidTransport(HidUnavailableReason.BLUETOOTH_UNSUPPORTED, statusListener)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return NullHidTransport(HidUnavailableReason.ANDROID_TOO_OLD, statusListener)
        }
        return HidDeviceTransport(
            context = context,
            bluetoothManager = bluetoothManager,
            statusListener = statusListener,
        )
    }

    /**
     * 运行期降级用的空实现。
     *
     * 典型用法：收到 `onUnavailable(PROFILE_UNAVAILABLE)` 后，UI 提示用户，
     * 同时把当前实现替换成 `fallback(reason, listener)`，保证后续事件不再打到系统层。
     */
    fun fallback(reason: HidUnavailableReason, statusListener: HidStatusListener): HidTransport =
        NullHidTransport(reason, statusListener)

    /** 系统蓝牙管理器便捷获取（拿不到返回 null）。 */
    fun bluetoothManager(context: Context): BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
}
