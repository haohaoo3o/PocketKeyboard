package com.pocketkeyboard.app.hid

import android.bluetooth.BluetoothManager
import android.content.Context
import com.pocketkeyboard.app.ui.MainViewModel

/**
 * HID 后统一入口：把 [HidTransport] 实现与 [MainViewModelStatusBridge] 组装起来。
 *
 * 典型用法（MainActivity / 配对页）：
 * ```kotlin
 * val controller = remember { HidController(applicationContext, viewModel) }
 * DisposableEffect(Unit) {
 *     controller.start()
 *     onDispose { controller.stop() }
 * }
 * // 键盘页 / 触控板页只依赖 controller.transport
 * controller.transport.sendKeyboardReport(modifiers, keys)
 * ```
 *
 * 这样 UI 层只看到 [HidTransport] 契约接口与 `controller.unavailableReason`，
 * 不需要知道背后是方案 A（BluetoothHidDevice）还是方案 B（空实现）。
 */
class HidController(
    context: Context,
    private val viewModel: MainViewModel,
    bluetoothManager: BluetoothManager? = HidTransportFactory.bluetoothManager(context),
) {

    /** 状态桥（先于 transport 初始化）。 */
    val bridge = MainViewModelStatusBridge(viewModel)

    /** 传输实现：方案 A 或方案 B，接口一致。 */
    val transport: HidTransport = HidTransportFactory.create(context, bluetoothManager, bridge)

    /** 连接不可用原因（null 表示未出现不可用情况）。 */
    val unavailableReason = bridge.unavailableReason

    /** 是否走方案 A（真·蓝牙 HID 外设）。 */
    val isProfileSupported: Boolean
        get() = transport is HidDeviceTransport

    /** 方案 A 实现的附加能力（多设备切换等），方案 B 下为 null。 */
    private val deviceTransport: HidDeviceTransport?
        get() = transport as? HidDeviceTransport

    fun start() {
        transport.start()
    }

    fun stop() {
        transport.stop()
    }

    /** 切换当前控制目标。 */
    fun setActiveDevice(address: String?) {
        deviceTransport?.setActiveDevice(address)
    }

    /** 在已连接设备间循环切换（五指左右滑手势）。 */
    fun cycleActiveDevice() {
        deviceTransport?.cycleActiveDevice()
    }

    /** 主动连接某个已配对设备。 */
    fun connectHost(address: String) {
        deviceTransport?.connectHost(address)
    }

    /** 断开某个设备。 */
    fun disconnectHost(address: String) {
        deviceTransport?.disconnectHost(address)
    }

    /** 当前已连接设备。 */
    fun connectedDevices(): List<HidDeviceInfo> = deviceTransport?.connectedDevices() ?: emptyList()
}
