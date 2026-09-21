package com.pocketkeyboard.app.hid

import android.util.Log

/**
 * 方案 B：HID 外设不可用时的空实现（接口与 [HidDeviceTransport] 完全一致）。
 *
 * 用途：
 * 1. 本机没有蓝牙硬件 / 蓝牙未开启 / 系统低于 Android 9（API 28，`BluetoothHidDevice`
 *    从该版本才对外开放）时，工程仍可编译、UI 仍可运行；
 * 2. 某些定制 ROM 直接禁用 HidDevice profile（`getProfileProxy` 不回调），
 *    或已有其他应用占用了唯一的 HID 外设注册位时，由 [HidTransportFactory] /
 *    运行期降级切换到本实现；
 * 3. 单元测试里当「哑」传输使用。
 *
 * 所有 `sendXxx` 都是 no-op，只打日志；[start] 会把不可用原因通过
 * [HidStatusListener.onUnavailable] 上报，UI 展示对应中文提示（见 `HidStatusText`）。
 */
class NullHidTransport(
    /** 不可用原因。 */
    val reason: HidUnavailableReason,
    private val statusListener: HidStatusListener? = null,
) : HidTransport {

    private var started = false

    /** 已上报过不可用原因（避免重复提示）。 */
    var hasReported: Boolean = false
        private set

    override fun start() {
        if (started) return
        started = true
        if (!hasReported) {
            hasReported = true
            Log.i(TAG, "HID transport unavailable: $reason")
            statusListener?.onUnavailable(reason)
        }
    }

    override fun stop() {
        started = false
    }

    override fun sendKeyboardReport(modifiers: Int, keyCodes: ByteArray) {
        logDropped("sendKeyboardReport(modifiers=$modifiers, keys=${keyCodes.size})")
    }

    override fun sendConsumerUsage(usage: Int) {
        logDropped("sendConsumerUsage(usage=0x${usage.toString(16)})")
    }

    override fun sendMouseMove(dx: Int, dy: Int) {
        logDropped("sendMouseMove(dx=$dx, dy=$dy)")
    }

    override fun sendScroll(dx: Int, dy: Int) {
        logDropped("sendScroll(dx=$dx, dy=$dy)")
    }

    override fun sendMouseButton(button: MouseButton, pressed: Boolean) {
        logDropped("sendMouseButton($button, pressed=$pressed)")
    }

    private fun logDropped(what: String) {
        Log.d(TAG, "dropped $what (reason=$reason)")
    }

    companion object {
        private const val TAG = "NullHidTransport"
    }
}
