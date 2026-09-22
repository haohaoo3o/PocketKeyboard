package com.pocketkeyboard.app.hid

/**
 * HID 外设后端的「系统蓝牙」抽象边界（纯 Kotlin 接口，便于 fake 单测）。
 *
 * [HidDeviceTransport] 只依赖这里的接口与 [HidProfileCallback]，不直接 import
 * `android.bluetooth.*`；Android 侧实现见 `SystemHidProfileGateway` / `SystemBondEventSource`。
 * 这样「连接状态机、重连策略、报告分发」等核心逻辑可以在普通 JVM 单元测试里完整覆盖。
 */

/** HID 主机（对端被控设备）在本层的表示。 */
data class HidHost(val address: String, val name: String?, val bondState: HidBondState)

/** SDP 记录参数，等价于 `BluetoothHidDeviceAppSdpSettings(name, description, provider, subclass, descriptors)`。 */
data class HidSdpRecord(
    val name: String,
    val description: String,
    val provider: String,
    /** `BluetoothHidDevice.SUBCLASS1_*`，键鼠复合设备用 `SUBCLASS1_COMBO`。 */
    val subclass: Int,
    val descriptors: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HidSdpRecord) return false
        return name == other.name &&
            description == other.description &&
            provider == other.provider &&
            subclass == other.subclass &&
            descriptors.contentEquals(other.descriptors)
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + provider.hashCode()
        result = 31 * result + subclass
        result = 31 * result + descriptors.contentHashCode()
        return result
    }
}

/**
 * QoS 参数，等价于
 * `BluetoothHidDeviceAppQosSettings(serviceType, tokenRate, tokenBucketSize, peakBandwidth, latency, delayVariation)`。
 *
 * `BluetoothHidDeviceAppQosSettings.MAX`（= -1）表示「不限」。
 */
data class HidQosSettings(
    /** `SERVICE_NO_TRAFFIC` / `SERVICE_BEST_EFFORT` / `SERVICE_GUARANTEED`。 */
    val serviceType: Int,
    val tokenRate: Int,
    val tokenBucketSize: Int,
    val peakBandwidth: Int,
    val latency: Int,
    val delayVariation: Int,
) {
    companion object {
        const val SERVICE_NO_TRAFFIC = 0
        const val SERVICE_BEST_EFFORT = 1
        const val SERVICE_GUARANTEED = 2

        /** 与 SDK 一致的「不限」值。 */
        const val MAX = -1

        /**
         * 键盘 / 鼠标类交互式输入的推荐 QoS：Guaranteed、8 字节/帧、低延迟。
         *
         * 数值取社区 HID 外设实现的通用取值（token rate 800 → 每 100ms 8 字节）。
         */
        fun interactive(): HidQosSettings = HidQosSettings(
            serviceType = SERVICE_GUARANTEED,
            tokenRate = 800,
            tokenBucketSize = 800,
            peakBandwidth = 10000,
            latency = MAX,
            delayVariation = MAX,
        )
    }
}

/**
 * `BluetoothHidDevice.Callback` 的纯 Kotlin 镜像（参数全部换成 address / 基本类型）。
 *
 * 与 SDK 回调一一对应：
 * - [onAppStatusChanged] ↔ `Callback.onAppStatusChanged(BluetoothDevice, boolean)`
 * - [onConnectionStateChanged] ↔ `Callback.onConnectionStateChanged(BluetoothDevice, int)`
 * - [onGetReport] ↔ `Callback.onGetReport(BluetoothDevice, byte, byte, int)`
 * - [onSetReport] ↔ `Callback.onSetReport(BluetoothDevice, byte, byte, byte[])`
 * - [onSetProtocol] ↔ `Callback.onSetProtocol(BluetoothDevice, byte)`
 * - [onInterruptData] ↔ `Callback.onInterruptData(BluetoothDevice, byte, byte[])`
 * - [onVirtualCableUnplug] ↔ `Callback.onVirtualCableUnplug(BluetoothDevice)`
 *   （虚拟线缆被拔掉：主机主动断开，应做重连准备）
 */
interface HidProfileCallback {
    fun onAppStatusChanged(pluggedDeviceAddress: String?, registered: Boolean) {}
    fun onConnectionStateChanged(address: String, state: Int) {}
    fun onGetReport(address: String, type: Int, reportId: Int, bufferSize: Int) {}
    fun onSetReport(address: String, type: Int, reportId: Int, data: ByteArray) {}
    fun onSetProtocol(address: String, protocolMode: Int) {}
    fun onInterruptData(address: String, reportId: Int, data: ByteArray) {}
    fun onVirtualCableUnplug(address: String) {}
}

/**
 * BluetoothHidDevice profile 代理的抽象。
 *
 * 所有返回 `Boolean` 的方法语义与 SDK 一致：**只表示「命令是否成功下发」**，
 * 真正结果通过 [HidProfileCallback] 异步回来（例如 registerApp 成功后才会
 * 回调 onAppStatusChanged(registered = true)）。
 */
interface HidProfileGateway {

    /** 是否已取得 profile 代理。 */
    val isReady: Boolean

    /**
     * 异步获取 HID_DEVICE profile 代理（对应 `BluetoothAdapter.getProfileProxy`）。
     *
     * @param onReady 代理就绪
     * @param onFailed 代理不可用（ROM 禁用 / 蓝牙关闭 / 权限不足），参数为不可用原因
     */
    fun open(onReady: () -> Unit, onFailed: (HidUnavailableReason) -> Unit)

    /** 释放 profile 代理（`closeProfileProxy`）。 */
    fun close()

    /** 注册 HID 外设 App（`registerApp`）。 */
    fun registerApp(
        sdp: HidSdpRecord,
        inQos: HidQosSettings?,
        outQos: HidQosSettings?,
        callback: HidProfileCallback,
    ): Boolean

    /** 注销 HID 外设 App（`unregisterApp`）。 */
    fun unregisterApp(): Boolean

    /** 主动连接某个已配对主机（`connect`）。 */
    fun connect(address: String): Boolean

    /** 断开某个主机（`disconnect`）。 */
    fun disconnect(address: String): Boolean

    /** 发送输入报告（`sendReport(device, id, data)`，data 不含 Report ID）。 */
    fun sendReport(address: String, reportId: Int, data: ByteArray): Boolean

    /** 响应主机的 GET_REPORT（`replyReport`）。 */
    fun replyReport(address: String, reportType: Int, reportId: Int, data: ByteArray): Boolean

    /** 上报报告错误（`reportError`）。 */
    fun reportError(address: String, error: Int): Boolean

    /** 当前已建立 HID 连接的主机（`getConnectedDevices`）。 */
    fun connectedHosts(): List<HidHost>

    /** 指定主机的 HID 连接状态（`getConnectionState`，取 `BluetoothProfile.STATE_*`）。 */
    fun connectionState(address: String): Int

    /** 系统已配对（bonded）设备列表（`BluetoothAdapter.getBondedDevices`）。 */
    fun bondedHosts(): List<HidHost>

    /**
     * 设置本机蓝牙名称（`BluetoothAdapter.setName`）：被控设备在蓝牙菜单里看到的
     * **广播名**。App 用它把设备名与手机系统名区分开（如 `PocketKeyboard-redmi`）。
     *
     * 默认实现返回 false（测试 / 无蓝牙硬件），生产实现在 [SystemHidProfileGateway]。
     */
    fun setLocalName(name: String): Boolean = false
}

/** bond / 配对 / 蓝牙开关事件源（`BroadcastReceiver` 的抽象）。 */
interface BondEventSource {
    fun start(listener: BondEventListener)
    fun stop()
}

/**
 * bond / 配对事件监听。
 *
 * [onBondEvent] 的返回值只对 [BondEvent.PairingRequest] 有意义：**true = App 已接管
 * 本次配对请求**（已自动应答，或已在 App 内展示配对码/收集输入），事件源应当
 * `abortBroadcast()` 抑制系统配对框；false = 未接管，交给系统配对对话框兜底。
 */
fun interface BondEventListener {
    fun onBondEvent(event: BondEvent): Boolean
}

/** bond / 配对事件。 */
sealed interface BondEvent {
    /** bond 状态变化。 */
    data class BondStateChanged(val address: String, val state: HidBondState) : BondEvent

    /** 系统配对请求（数字比较 / Just Works / PIN）。 */
    data class PairingRequest(
        val address: String,
        val variant: HidPairingVariant,
        val pinOrPasskey: Int?,
    ) : BondEvent

    /** 蓝牙适配器开关状态变化（true = 已开启）。 */
    data class AdapterStateChanged(val enabled: Boolean) : BondEvent
}
