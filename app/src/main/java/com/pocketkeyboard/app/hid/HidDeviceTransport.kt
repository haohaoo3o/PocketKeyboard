package com.pocketkeyboard.app.hid

import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 方案 A：基于 Android `BluetoothHidDevice` profile 的 HID 外设传输实现。
 *
 * ## 角色
 * 手机是 HID **Device（外设）**，对端（iPad / iPhone / Mac / Windows）是 HID Host。
 * 本类实现跨模块契约 [HidTransport]，把键盘 / 媒体键 / 鼠标 / 滚轮事件转成 HID 输入报告，
 * 通过 `BluetoothHidDevice.sendReport(device, reportId, data)` 发给当前控制目标。
 *
 * ## 关键 API 调用链
 * ```
 * start()
 *  └─ HidProfileGateway.open()                       // getProfileProxy(HID_DEVICE)
 *       └─ onProfileReady()
 *            ├─ refreshBondedDevices()               // BluetoothAdapter.getBondedDevices()
 *            ├─ gateway.registerApp(sdp, inQos, outQos, callback)
 *            │     sdp = BluetoothHidDeviceAppSdpSettings(
 *            │            name = 「口袋键鼠」, description, provider,
 *            │            subclass = BluetoothHidDevice.SUBCLASS1_COMBO,
 *            │            descriptors = HidReportDescriptor.bytes)
 *            │     // 成功后回调 onAppStatusChanged(pluggedDevice, registered = true)
 *            └─ BondEventSource.start()              // ACTION_BOND_STATE_CHANGED / ACTION_PAIRING_REQUEST
 *
 * 对端发起连接（或我方 connect()）
 *  └─ Callback.onConnectionStateChanged(device, STATE_CONNECTED)
 *       └─ registry 更新 + statusListener.onHostConnected + 选定 activeDevice
 *
 * sendKeyboardReport/sendConsumerUsage/sendMouseMove/sendScroll/sendMouseButton
 *  └─ HidReportFactory 构造报告 → gateway.sendReport(activeDevice, reportId, data)
 * ```
 *
 * ## 配对（SSP + 自动应答，Bug 2a）
 * Android 无法完全自定义系统配对 UI，但**可以代劳应答**：被控端发起配对时系统发出
 * `BluetoothDevice.ACTION_PAIRING_REQUEST`（由 [SystemBondEventSource] 在 hid 包内注册
 * 接收，`RECEIVER_NOT_EXPORTED` + `BLUETOOTH_CONNECT` 权限防护），本类在
 * [onBondEvent] 里按 variant 交给 [SystemPairingResponder]：
 * - `PASSKEY_ENTRY` / `PIN`：反射 `device.setPin(App 配对码)`，被控端输入 App 展示的
 *   6 位码即可完成配对（不再依赖用户在系统 SSP 对话框两边确认）；
 * - `PASSKEY_CONFIRMATION` / `CONSENT`：反射 `device.setPairingConfirmation(true)` 自动确认。
 *
 * 前置条件：**App 处于前台且 HID 外设已注册**（[appInForeground] + [isAppRegistered]）；
 * 任一不满足、或反射失败（[PairingAnswer.FAILED] / [PairingAnswer.SKIPPED]）时不做任何
 * 补救，系统配对对话框照常弹出，用户按原流程手动完成——绝不崩溃。
 * bond 成功后沿用原有链路：`ACTION_BOND_STATE_CHANGED(BONDED)` → registry 登记 →
 * `connectHost`，并经 bridge 更新 MainViewModel 的配对码与连接状态。
 *
 * ## 多设备
 * - [HidHostRegistry] 维护已连接主机集合与连接顺序；
 * - 所有 `sendXxx` 只发给 `registry.preferredTarget()`（默认当前 activeDevice）；
 * - 主机断开后按 [ReconnectPolicy] 指数退避重连；
 * - 五指左右滑切设备：调用 [cycleActiveDevice]。
 *
 * ## 可测性
 * 构造参数全部是接口（[HidProfileGateway] / [BondEventSource] / [ReconnectScheduler]），
 * 单测用 fake 注入即可完整驱动状态机，无需 Android 设备。
 *
 * ## 线程
 * 所有回调都跑在传入 gateway 的 executor 上（生产环境是主线程），UI 也在主线程调用
 * `sendXxx`，因此状态机天然串行；内部仍用 [lock] 兜底保护。
 *
 * @param statusListener 状态出口（由 bridge 转给 MainViewModel）。
 */
class HidDeviceTransport(
    private val statusListener: HidStatusListener,
    private val gateway: HidProfileGateway,
    private val bondEventSource: BondEventSource,
    private val reconnectScheduler: ReconnectScheduler = HandlerReconnectScheduler(),
    private val pairingResponder: PairingResponder = NoOpPairingResponder(),
    private val appInForeground: () -> Boolean = { true },
) : HidTransport, HidProfileCallback {

    /**
     * 生产环境构造：使用系统实现（BluetoothManager / HidDevice profile / bond 广播）。
     *
     * @param context Application Context。
     * @param bluetoothManager 系统蓝牙管理器，null 表示本机无蓝牙（此时 start() 会上报不可用）。
     */
    constructor(
        context: Context,
        bluetoothManager: BluetoothManager?,
        statusListener: HidStatusListener,
    ) : this(
        statusListener = statusListener,
        gateway = SystemHidProfileGateway(context, bluetoothManager),
        bondEventSource = SystemBondEventSource(context),
        reconnectScheduler = HandlerReconnectScheduler(),
        // Bug 2a：配对码自动应答。配对码来源是 bridge（读 MainViewModel.pinCode），
        // 因此这里按接口转型，非 bridge 的 listener（纯测试）传 null → 不抢系统的配对框。
        pairingResponder = SystemPairingResponder(
            pinCodeProvider = statusListener as? PairingPinProvider,
            targetProvider = ReflectionPairingTargetProvider(context),
        ),
        appInForeground = SystemAppVisibility(context)::isForeground,
    )

    private val lock = Any()

    /** 已连接 / 已配对主机登记表。 */
    val registry = HidHostRegistry()

    /** HID 外设 App 是否已注册成功。 */
    var isAppRegistered: Boolean = false
        private set

    /** 主机当前协议模式：`BluetoothHidDevice.PROTOCOL_BOOT_MODE` / `PROTOCOL_REPORT_MODE`。 */
    var protocolMode: Int = BluetoothHidDevice.PROTOCOL_REPORT_MODE.toInt()
        private set

    /** 最近一次 LED 输出报告（bit0 Num Lock / bit1 Caps Lock / bit2 Scroll Lock）。 */
    var ledState: Int = 0
        private set

    private var started = false
    private var activeButtonMask = 0
    private val reconnectAttempts = HashMap<String, Int>()
    private var lastKeyboardReport: ByteArray = HidReportFactory.keyboardRelease()
    private var lastMouseReport: ByteArray = HidReportFactory.mouse(0, 0, 0, 0)

    // ============================================================ HidTransport

    override fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        gateway.open(
            onReady = { onProfileReady() },
            onFailed = { reason ->
                // open 失败（最常见：权限尚未授予）时复位 started，
                // 否则权限授予后的重试 start() 会被 started 标志直接吞掉
                synchronized(lock) { started = false }
                statusListener.onUnavailable(reason)
            },
        )
    }

    override fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
            reconnectScheduler.cancelAll()
            reconnectAttempts.clear()
            registry.allEntries().forEach { registry.onDisconnected(it.address) }
            activeButtonMask = 0
            isAppRegistered = false
        }
        bondEventSource.stop()
        gateway.unregisterApp()
        gateway.close()
        statusListener.onAppRegistrationChanged(false)
        statusListener.onActiveDeviceChanged(null)
        publishConnectedHosts()
    }

    override fun sendKeyboardReport(modifiers: Int, keyCodes: ByteArray) {
        val report = HidReportFactory.keyboard(modifiers, keyCodes)
        synchronized(lock) { lastKeyboardReport = report }
        sendToActive(HidReportFactory.REPORT_ID_KEYBOARD, report)
    }

    override fun sendConsumerUsage(usage: Int) {
        sendToActive(HidReportFactory.REPORT_ID_CONSUMER, HidReportFactory.consumer(usage))
    }

    override fun sendMouseMove(dx: Int, dy: Int) {
        synchronized(lock) {
            val report = HidReportFactory.mouse(activeButtonMask, dx, dy, 0)
            lastMouseReport = report
            sendToActive(HidReportFactory.REPORT_ID_MOUSE, report)
        }
    }

    override fun sendScroll(dx: Int, dy: Int) {
        synchronized(lock) {
            // 水平滚轮（AC Pan）需要 5 字节扩展报告（Report ID 5）；
            // 只有垂直滚动时始终走 boot 兼容的 4 字节报告（Report ID 3）。
            val report = if (dx != 0) {
                HidReportFactory.mouseWithPan(activeButtonMask, 0, 0, dy, dx)
            } else {
                HidReportFactory.mouse(activeButtonMask, 0, 0, dy)
            }
            lastMouseReport = report
            sendToActive(
                if (dx != 0) HidReportFactory.REPORT_ID_MOUSE_PAN else HidReportFactory.REPORT_ID_MOUSE,
                report,
            )
        }
    }

    override fun sendMouseButton(button: MouseButton, pressed: Boolean) {
        val bit = when (button) {
            MouseButton.LEFT -> HidReportFactory.BUTTON_LEFT
            MouseButton.RIGHT -> HidReportFactory.BUTTON_RIGHT
        }
        synchronized(lock) {
            activeButtonMask = if (pressed) {
                activeButtonMask or bit
            } else {
                activeButtonMask and bit.inv()
            }
            val report = HidReportFactory.mouse(activeButtonMask, 0, 0, 0)
            lastMouseReport = report
            sendToActive(HidReportFactory.REPORT_ID_MOUSE, report)
        }
    }

    // ============================================================ 多设备控制（契约外附加 API）

    /** 切换当前控制目标（配对页点击列表项 / 五指手势切设备）。 */
    fun setActiveDevice(address: String?) {
        synchronized(lock) {
            if (registry.setActive(address)) {
                statusListener.onActiveDeviceChanged(address?.let { registry.deviceInfo(it) })
            }
        }
    }

    /** 在已连接主机之间循环切换控制目标，返回切换后的设备信息（无变化返回 null）。 */
    fun cycleActiveDevice(): HidDeviceInfo? = synchronized(lock) {
        val next = registry.cycleActive() ?: return null
        val info = registry.deviceInfo(next)
        statusListener.onActiveDeviceChanged(info)
        info
    }

    /** 主动连接某个已配对主机。 */
    fun connectHost(address: String) {
        synchronized(lock) {
            registry.updateConnectionState(address, HidHostRegistry.STATE_CONNECTING)
            if (!gateway.connect(address)) {
                registry.updateConnectionState(address, HidHostRegistry.STATE_DISCONNECTED)
                scheduleReconnect(address)
            }
        }
    }

    /** 断开某个主机。 */
    fun disconnectHost(address: String) {
        synchronized(lock) {
            reconnectScheduler.cancel(address)
            reconnectAttempts.remove(address)
            gateway.disconnect(address)
            registry.onDisconnected(address)
            statusListener.onHostDisconnected(address)
        }
    }

    /** 当前已连接主机（连接顺序）。 */
    fun connectedDevices(): List<HidDeviceInfo> = synchronized(lock) {
        registry.connectedEntries().map {
            HidDeviceInfo(it.address, it.name ?: it.address, it.platform)
        }
    }

    /** 当前控制目标。 */
    fun activeDevice(): HidDeviceInfo? = synchronized(lock) {
        registry.activeAddress?.let { registry.deviceInfo(it) }
    }

    // ============================================================ HidProfileCallback

    override fun onAppStatusChanged(pluggedDeviceAddress: String?, registered: Boolean) {
        Log.i(TAG, "onAppStatusChanged registered=$registered plugged=$pluggedDeviceAddress")
        synchronized(lock) {
            isAppRegistered = registered
            if (!registered) {
                // 系统可能因蓝牙关闭 / App 退到后台而自动注销
                reconnectScheduler.cancelAll()
                reconnectAttempts.clear()
                registry.allEntries().forEach { registry.onDisconnected(it.address) }
                activeButtonMask = 0
            }
        }
        statusListener.onAppRegistrationChanged(registered)
        if (registered) {
            refreshBondedDevices()
            refreshConnectedHosts()
            if (pluggedDeviceAddress != null) {
                // Bug 2b：plugged 设备 = 「虚拟线缆已插上」= 有主机连着本外设。
                // 必须显式登记为已连接：实测（小米 21091116UC + Android 13）
                // `BluetoothHidDevice.getConnectedDevices()` 在 plugged 设备存在时仍返回
                // 空列表，`onConnectionStateChanged` 也不会为存量连接补发事件，
                // 只靠 refreshConnectedHosts() 会让 UI 的「已连接」永远不显示。
                val becameConnected = synchronized(lock) {
                    registry.updateConnectionState(
                        pluggedDeviceAddress,
                        HidHostRegistry.STATE_CONNECTED,
                    )
                }
                val info = synchronized(lock) { registry.deviceInfo(pluggedDeviceAddress) }
                if (becameConnected) info?.let { statusListener.onHostConnected(it) }
                val activeBefore = synchronized(lock) { registry.activeAddress }
                synchronized(lock) { registry.setActive(pluggedDeviceAddress) }
                if (registryActiveAddress() != activeBefore) {
                    statusListener.onActiveDeviceChanged(
                        synchronized(lock) { registry.deviceInfo(pluggedDeviceAddress) },
                    )
                }
            }
            publishConnectedHosts()
            if (pluggedDeviceAddress == null) {
                tryConnectPreferredTarget()
            }
        } else {
            statusListener.onActiveDeviceChanged(null)
            publishConnectedHosts()
        }
    }

    /** 读 registry 的当前控制目标（锁外调用点用）。 */
    private fun registryActiveAddress(): String? = synchronized(lock) { registry.activeAddress }

    override fun onConnectionStateChanged(address: String, state: Int) {
        Log.i(TAG, "onConnectionStateChanged $address state=$state")
        synchronized(lock) {
            val activeBefore = registry.activeAddress
            val becameConnected = registry.updateConnectionState(address, state)
            val entry = registry.entry(address)
            registry.updatePlatform(address, DevicePlatformDetector.detect(entry?.name, address))
            val isBonded = entry?.isBonded == true
            val isDisconnected = state == HidHostRegistry.STATE_DISCONNECTED ||
                state == HidHostRegistry.STATE_DISCONNECTING
            if (state == HidHostRegistry.STATE_CONNECTED) {
                reconnectAttempts.remove(address)
                reconnectScheduler.cancel(address)
                // 只在还没有控制目标时把新连接的设备设为目标，不抢夺用户已选定的设备
                if (registry.activeAddress == null) {
                    registry.setActive(address)
                }
            } else if (isDisconnected) {
                registry.onDisconnected(address)
            }
            val info = registry.deviceInfo(address)
            if (becameConnected) info?.let { statusListener.onHostConnected(it) }
            if (isDisconnected) statusListener.onHostDisconnected(address)
            // 目标发生变化（首个连接 / 目标断开让位）时才通知，避免抢目标
            if (registry.activeAddress != activeBefore) {
                statusListener.onActiveDeviceChanged(
                    registry.activeAddress?.let { registry.deviceInfo(it) },
                )
            }
            if (isDisconnected && isBonded) scheduleReconnect(address)
        }
        publishConnectedHosts()
    }

    override fun onGetReport(address: String, type: Int, reportId: Int, bufferSize: Int) {
        // 主机在连接后常会 GET_REPORT 拉取当前状态；按类型回一份「当前快照」。
        val reply: ByteArray = synchronized(lock) {
            when (type) {
                BluetoothHidDevice.REPORT_TYPE_OUTPUT.toInt() -> {
                    // 键盘 LED 输出报告（Num Lock / Caps Lock / Scroll Lock）
                    byteArrayOf(ledState.toByte())
                }
                BluetoothHidDevice.REPORT_TYPE_INPUT.toInt() -> when (reportId) {
                    HidReportFactory.REPORT_ID_KEYBOARD -> lastKeyboardReport.copyOf()
                    HidReportFactory.REPORT_ID_CONSUMER -> HidReportFactory.consumerRelease()
                    else -> lastMouseReport.copyOf()
                }
                else -> ByteArray(bufferSize.coerceAtLeast(0))
            }
        }
        gateway.replyReport(address, type, reportId, reply)
    }

    override fun onSetReport(address: String, type: Int, reportId: Int, data: ByteArray) {
        if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT.toInt() &&
            reportId == HidReportFactory.REPORT_ID_KEYBOARD
        ) {
            val newState = if (data.isNotEmpty()) data[0].toInt() and 0xFF else 0
            synchronized(lock) {
                if (newState != ledState) {
                    ledState = newState
                    statusListener.onLedStateChanged(
                        numLock = newState and 0x01 != 0,
                        capsLock = newState and 0x02 != 0,
                        scrollLock = newState and 0x04 != 0,
                    )
                }
            }
        }
    }

    override fun onSetProtocol(address: String, protocolMode: Int) {
        synchronized(lock) { this.protocolMode = protocolMode }
        Log.i(TAG, "onSetProtocol mode=$protocolMode (0=boot, 1=report)")
    }

    override fun onInterruptData(address: String, reportId: Int, data: ByteArray) {
        // BR/EDR HID 下主机很少通过 interrupt 通道下发数据，仅记录日志。
        Log.d(TAG, "onInterruptData from $address report=$reportId size=${data.size}")
    }

    override fun onVirtualCableUnplug(address: String) {
        // 主机主动断开虚拟线缆（相当于「拔线」）：清理状态并准备重连。
        Log.i(TAG, "onVirtualCableUnplug $address")
        synchronized(lock) {
            registry.onDisconnected(address)
            activeButtonMask = 0
        }
        statusListener.onHostDisconnected(address)
        scheduleReconnect(address)
        publishConnectedHosts()
    }

    // ============================================================ 内部实现

    private fun onProfileReady() {
        refreshBondedDevices()
        bondEventSource.start(::onBondEvent)
        val sdp = HidSdpRecord(
            name = SDP_RECORD_NAME,
            description = SDP_RECORD_DESCRIPTION,
            provider = SDP_RECORD_PROVIDER,
            subclass = BluetoothHidDevice.SUBCLASS1_COMBO.toInt(),
            descriptors = HidReportDescriptor.bytes,
        )
        val qos = HidQosSettings.interactive()
        val accepted = gateway.registerApp(sdp, qos, qos, this)
        if (!accepted) {
            // 注意：registerApp 的返回值只代表「命令是否下发成功」，真正结果看
            // onAppStatusChanged；返回 false 通常意味着代理未就绪或参数非法。
            Log.w(TAG, "registerApp command rejected")
            statusListener.onUnavailable(HidUnavailableReason.REGISTRATION_REJECTED)
        }
    }

    private fun onBondEvent(event: BondEvent) {
        when (event) {
            is BondEvent.BondStateChanged -> onBondStateChanged(event.address, event.state)
            is BondEvent.PairingRequest -> {
                // Bug 2a：ACTION_PAIRING_REQUEST 到达后先尝试按 variant 自动应答。
                // 前置条件（前台 + HID 已注册）不满足时直接跳过——系统配对框是兜底，
                // 用户仍可手动确认；反射失败同样回退系统框（见 SystemPairingResponder）。
                if (isAppRegistered && appInForeground()) {
                    val answer = pairingResponder.answer(
                        event.address,
                        event.variant,
                        event.pinOrPasskey,
                    )
                    Log.i(TAG, "pairing auto-answer for ${event.address}: $answer")
                }
                statusListener.onPairingRequest(
                    event.address,
                    event.variant,
                    event.pinOrPasskey,
                )
            }
            is BondEvent.AdapterStateChanged -> onAdapterStateChanged(event.enabled)
        }
    }

    private fun onBondStateChanged(address: String, state: HidBondState) {
        Log.i(TAG, "bond state $address -> $state")
        synchronized(lock) {
            registry.updateBondState(address, state)
        }
        statusListener.onBondStateChanged(address, state)
        when (state) {
            HidBondState.BONDED -> {
                // 刚配对成功：把新设备加入已配对列表，并尝试连接
                refreshBondedDevices()
                statusListener.onPairedDevicesChanged(pairedDeviceInfos())
                connectHost(address)
            }
            HidBondState.NONE -> {
                synchronized(lock) {
                    reconnectScheduler.cancel(address)
                    reconnectAttempts.remove(address)
                    registry.onBondRemoved(address)
                }
                statusListener.onPairedDevicesChanged(pairedDeviceInfos())
                statusListener.onActiveDeviceChanged(activeDevice())
            }
            else -> Unit
        }
    }

    private fun onAdapterStateChanged(enabled: Boolean) {
        if (enabled) {
            synchronized(lock) {
                if (!started) return
            }
            // 蓝牙重新打开：重新走一遍「取代理 → 注册」
            gateway.open(
                onReady = { onProfileReady() },
                onFailed = { reason -> statusListener.onUnavailable(reason) },
            )
        } else {
            synchronized(lock) {
                reconnectScheduler.cancelAll()
                reconnectAttempts.clear()
                registry.allEntries().forEach { registry.onDisconnected(it.address) }
                activeButtonMask = 0
                isAppRegistered = false
            }
            statusListener.onAppRegistrationChanged(false)
            statusListener.onActiveDeviceChanged(null)
            statusListener.onUnavailable(HidUnavailableReason.BLUETOOTH_OFF)
        }
    }

    /** 用 `BluetoothAdapter.getBondedDevices()` 刷新已配对列表。 */
    private fun refreshBondedDevices() {
        val hosts = gateway.bondedHosts()
        synchronized(lock) {
            hosts.forEach { host ->
                val platform = DevicePlatformDetector.detect(host.name, host.address)
                registry.update(host.address) { existing ->
                    (existing ?: HidHostRegistry.Entry(host.address, host.name)).copy(
                        name = host.name ?: existing?.name,
                        bondState = host.bondState,
                        platform = platform,
                    )
                }
            }
        }
        statusListener.onPairedDevicesChanged(pairedDeviceInfos())
    }

    /**
     * 推送「当前已连接 HID 对端」全量快照（Bug 2b：配对页的「已连接」标识数据源）。
     *
     * 在一切可能改变连接集合的路径之后调用：连接状态变化、注册 / 注销、虚拟线缆拔掉、
     * stop()。用快照而不是依赖 onHostConnected/onHostDisconnected 的增量，避免漏事件
     * 导致 UI 标识与真实状态漂移。
     */
    private fun publishConnectedHosts() {
        val connected = synchronized(lock) { registry.connectedAddresses() }
        Log.i(TAG, "publishConnectedHosts: registry connected = $connected")
        statusListener.onConnectedDevicesChanged(connected.toSet())
    }

    /**
     * 刷新已连接列表：`getConnectedDevices()` 列表 + 逐个 `getConnectionState()` 兜底。
     *
     * 为什么不能只用前者：实测（小米 21091116UC + Android 13）HID 主机已连着本外设时
     * （`onAppStatusChanged` 带了 plugged 设备），`getConnectedDevices()` 仍返回空列表，
     * 配对页的「已连接」因此永远不显示（Bug 2b）。逐个查询 `getConnectionState()`
     * 覆盖这类 ROM 差异；任一来源说「已连接」即登记。
     */
    private fun refreshConnectedHosts() {
        val hosts = gateway.connectedHosts()
        Log.i(TAG, "refreshConnectedHosts: gateway reported ${hosts.map { it.address }}")
        synchronized(lock) {
            hosts.forEach { host ->
                registry.update(host.address) { existing ->
                    (existing ?: HidHostRegistry.Entry(host.address, host.name)).copy(
                        name = host.name ?: existing?.name,
                        bondState = host.bondState,
                        connectionState = HidHostRegistry.STATE_CONNECTED,
                        platform = DevicePlatformDetector.detect(host.name, host.address),
                    )
                }
            }
        }
        hosts.forEach { host ->
            statusListener.onHostConnected(
                HidDeviceInfo(
                    address = host.address,
                    name = host.name ?: host.address,
                    platform = DevicePlatformDetector.detect(host.name, host.address),
                ),
            )
        }
        // 兜底查询：地址在锁内快照，IPC 放在锁外，避免长时间持锁
        val known = synchronized(lock) { registry.allEntries().map { it.address } }
        known.forEach { address ->
            if (hosts.any { it.address.equals(address, ignoreCase = true) }) return@forEach
            val state = gateway.connectionState(address)
            Log.i(TAG, "refreshConnectedHosts: getConnectionState($address) = $state")
            if (state == HidHostRegistry.STATE_CONNECTED) {
                val becameConnected = synchronized(lock) {
                    registry.updateConnectionState(address, HidHostRegistry.STATE_CONNECTED)
                }
                if (becameConnected) {
                    val info = synchronized(lock) { registry.deviceInfo(address) }
                    info?.let { statusListener.onHostConnected(it) }
                }
            }
        }
        tryConnectPreferredTarget()
    }

    /** 没有活动目标时，挑一个已连接主机当目标。 */
    private fun tryConnectPreferredTarget() {
        synchronized(lock) {
            val target = registry.preferredTarget() ?: return
            if (registry.isConnected(target)) {
                statusListener.onActiveDeviceChanged(registry.deviceInfo(target))
            } else {
                connectHost(target)
            }
        }
    }

    private fun scheduleReconnect(address: String) {
        synchronized(lock) {
            val entry = registry.entry(address)
            // 明确知道「未配对」才放弃；bond 状态未知时仍然尝试（查询可能因权限失败）。
            if (entry != null && entry.bondState == HidBondState.NONE) return
            if (!started) return
            val attempt = (reconnectAttempts[address] ?: 0) + 1
            if (!ReconnectPolicy.shouldRetry(attempt)) {
                reconnectAttempts.remove(address)
                return
            }
            reconnectAttempts[address] = attempt
            val delay = ReconnectPolicy.delayFor(attempt)
            Log.i(TAG, "schedule reconnect $address attempt=$attempt delay=$delay")
            reconnectScheduler.cancel(address)
            reconnectScheduler.schedule(address, delay) {
                synchronized(lock) {
                    if (!started) return@synchronized
                    val current = registry.entry(address)
                    if (current == null || current.isConnected) return@synchronized
                    statusListener.onReconnecting(address, attempt)
                    if (!gateway.connect(address)) {
                        // connect 命令下发失败：继续退避重试
                        scheduleReconnect(address)
                    }
                }
            }
        }
    }

    /** 发送到当前控制目标；没有目标 / 未连接时返回 false 并尝试补救。 */
    private fun sendToActive(reportId: Int, data: ByteArray): Boolean {
        val address = synchronized(lock) {
            val target = registry.preferredTarget()
            if (target == null) {
                statusListener.onActiveDeviceChanged(null)
                return false
            }
            if (!registry.isConnected(target)) {
                null
            } else {
                target
            }
        } ?: run {
            // 目标未连接：尝试连接，本次事件丢弃（UI 层应已提示）
            synchronized(lock) { registry.activeAddress?.let { connectHost(it) } }
            return false
        }
        return gateway.sendReport(address, reportId, data)
    }

    /** 已配对设备列表（bonded 或已连接）。 */
    private fun pairedDeviceInfos(): List<HidDeviceInfo> = synchronized(lock) {
        registry.allEntries()
            .filter { it.isBonded || it.isConnected }
            .map { HidDeviceInfo(it.address, it.name ?: it.address, it.platform) }
    }

    companion object {
        private const val TAG = "HidDeviceTransport"

        /**
         * SDP 记录名：对端蓝牙菜单里显示的设备名（上限 50 字节）。
         * 「口袋键鼠」UTF-8 为 18 字节，安全。
         */
        const val SDP_RECORD_NAME = "口袋键鼠"

        /** SDP 描述。 */
        const val SDP_RECORD_DESCRIPTION = "蓝牙键盘与触控板"

        /** SDP 提供方。 */
        const val SDP_RECORD_PROVIDER = "口袋键鼠"
    }
}

/** 基于主线程 [Handler] 的重连调度器。 */
class HandlerReconnectScheduler(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : ReconnectScheduler {

    private val pending = HashMap<String, Runnable>()

    override fun schedule(address: String, delayMillis: Long, action: () -> Unit): () -> Unit {
        cancel(address)
        val runnable = Runnable { pending.remove(address); action() }
        pending[address] = runnable
        handler.postDelayed(runnable, delayMillis)
        return { cancel(address) }
    }

    override fun cancel(address: String) {
        pending.remove(address)?.let { handler.removeCallbacks(it) }
    }

    override fun cancelAll() {
        pending.values.forEach { handler.removeCallbacks(it) }
        pending.clear()
    }
}
