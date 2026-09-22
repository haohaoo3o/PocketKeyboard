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
 * 连接状态（A1：只认系统真值）
 *  └─ onConnectionStateChanged(device, STATE_CONNECTED)   // 系统回调：唯一真值通道
 *        └─ registry 更新 + statusListener.onHostConnected + 选定 activeDevice
 *  └─ refreshConnectedHosts()                        // 逐地址 getConnectionState() 兜底
 *
 * 主动连接（A2：设备侧发起 HID L2CAP）
 *  └─ connectHost(address) → gateway.connect(address)
 *        触发点：注册成功后自动连一次（plugged 线索 / preferredTarget）、
 *        点设备行、发送时发现未连接、退避重试
 *
 * sendKeyboardReport/sendConsumerUsage/sendMouseMove/sendScroll/sendMouseButton
 *  └─ HidReportFactory 构造报告 → sendToActive()
 *        ├─ 已连接 → gateway.sendReport(activeDevice, reportId, data)  // 打印返回值
 *        └─ 未连接 → 暂存状态型报告 + connectHost，连接成功后补发
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
 * ## 连接状态判定（A1）与连接建立（A2）
 * - **A1**：registry 的「已连接」只由系统真值驱动——`onConnectionStateChanged` 与
 *   [refreshConnectedHosts] 里逐地址 `getConnectionState() == STATE_CONNECTED`。
 *   `onAppStatusChanged` 的 plugged 设备（虚拟线缆已插上）在部分 ROM 上并不等于
 *   profile 层已连接（`getConnectedDevices()` 空 + `getConnectionState()` = 0），
 *   因此**不再**据此写 connected（那是假阳性），只用于自动选定控制目标与自动连接。
 * - **A2**：连接必须主动发起（设备侧 `BluetoothHidDevice.connect`）：注册成功后
 *   对 plugged / preferredTarget 连一次，点设备行、发送遇未连接、退避重试时同样
 *   主动连；发送遇未连接时暂存状态型报告，连接成功后补发。
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

    /** 注册自愈：已尝试的恢复轮数（onAppStatusChanged 成功时清零）。 */
    private var registrationAttempts = 0

    /** 注册看门狗的取消句柄（回调到达即取消）。 */
    private var registrationWatchdogCancel: (() -> Unit)? = null
    private val reconnectAttempts = HashMap<String, Int>()
    private var lastKeyboardReport: ByteArray = HidReportFactory.keyboardRelease()
    private var lastMouseReport: ByteArray = HidReportFactory.mouse(0, 0, 0, 0)

    /**
     * 因「目标未连接」而**暂存**的最近一次输入报告（Bug A2：连接建立后补发）。
     *
     * 只存状态型报告（键盘 / 鼠标，与 [lastKeyboardReport] / [lastMouseReport] 同一批），
     * 它们被更新的概率高于一次性事件；每次新的暂存会覆盖上一份，因此补发的永远是
     * 「最新的键鼠状态」而不是陈旧的按键。一次性事件（consumer 媒体键）不暂存——
     * 延迟补发会造成多余的音量跳变，直接丢弃并记日志。
     */
    private var pendingReport: PendingReport? = null

    /** [pendingReport] 的内容：目标地址 + 报告 ID + 数据。 */
    private data class PendingReport(
        val address: String,
        val reportId: Int,
        val data: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PendingReport) return false
            return address.equals(other.address, ignoreCase = true) &&
                reportId == other.reportId &&
                data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = address.lowercase().hashCode()
            result = 31 * result + reportId
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

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
            pendingReport = null
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
            if (registry.isConnected(address)) return
            if (registry.entry(address)?.connectionState == HidHostRegistry.STATE_CONNECTING) {
                // 已在下发连接中：不重复发起（重复 connect 会让状态机在
                // CONNECTING → DISCONNECTED 之间来回跳），只让 UI 保持「连接中」
                statusListener.onHostConnecting(address)
                return
            }
            registry.updateConnectionState(address, HidHostRegistry.STATE_CONNECTING)
            // UI 立刻显示「连接中…」；后续 connect 失败 / 成功都以此为准清理
            statusListener.onHostConnecting(address)
            val accepted = gateway.connect(address)
            Log.i(TAG, "gateway.connect($address) = $accepted")
            if (!accepted) {
                registry.updateConnectionState(address, HidHostRegistry.STATE_DISCONNECTED)
                statusListener.onHostConnectFailed(address)
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
            clearPendingReport(address)
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
        // 回调到达 = 注册流程有确定结果，看门狗退场；成功时自愈轮数清零
        registrationWatchdogCancel?.invoke()
        registrationWatchdogCancel = null
        if (registered) registrationAttempts = 0
        synchronized(lock) {
            isAppRegistered = registered
            if (!registered) {
                // 系统可能因蓝牙关闭 / App 退到后台而自动注销
                reconnectScheduler.cancelAll()
                reconnectAttempts.clear()
                registry.allEntries().forEach { registry.onDisconnected(it.address) }
                activeButtonMask = 0
                pendingReport = null
            }
        }
        statusListener.onAppRegistrationChanged(registered)
        if (registered) {
            // plugged 只是「虚拟线缆插着」的线索：部分 ROM（实测小米 + Android 13）
            // 上 getConnectedDevices() 返回空、getConnectionState() 还是 DISCONNECTED，
            // HID 连接并没有在 profile 层建立。Bug A1 起不再据此把 registry 写成
            // STATE_CONNECTED（那是假阳性）；registry 的已连接只认系统真值——
            // onConnectionStateChanged / refreshConnectedHosts 里逐地址
            // getConnectionState()==STATE_CONNECTED。plugged 现在只用于两件事：
            // 自动选定控制目标 + 自动发起连接（见下方 A2）。
            if (pluggedDeviceAddress != null) {
                synchronized(lock) {
                    registry.update(pluggedDeviceAddress) { existing ->
                        (existing ?: HidHostRegistry.Entry(pluggedDeviceAddress, null)).copy(
                            platform = DevicePlatformDetector.detect(
                                existing?.name,
                                pluggedDeviceAddress,
                            ),
                        )
                    }
                }
                // 自动选定控制目标（不含任何「已连接」判定）；目标真的换了才通知 UI
                if (synchronized(lock) { registry.setActive(pluggedDeviceAddress) }) {
                    statusListener.onActiveDeviceChanged(
                        synchronized(lock) { registry.deviceInfo(pluggedDeviceAddress) },
                    )
                }
            }
            refreshBondedDevices()
            refreshConnectedHosts()
            publishConnectedHosts()
            // A2：注册成功后主动发起一次连接——
            // - 有 plugged 线索（虚拟线缆已插上）→ 连它；
            // - 否则按 preferredTarget（已连上的主机 / 已选目标）。
            // 已连接的直接确认为控制目标；未连接的 connectHost 一次，
            // 失败由 reconnectScheduler 指数退避重试。
            val autoConnectTarget = synchronized(lock) {
                pluggedDeviceAddress?.takeIf { registry.entry(it) != null }
                    ?: registry.preferredTarget()
            }
            if (autoConnectTarget != null) {
                if (synchronized(lock) { registry.isConnected(autoConnectTarget) }) {
                    statusListener.onActiveDeviceChanged(
                        synchronized(lock) { registry.deviceInfo(autoConnectTarget) },
                    )
                } else {
                    connectHost(autoConnectTarget)
                }
            }
        } else {
            statusListener.onActiveDeviceChanged(null)
            publishConnectedHosts()
        }
    }

    override fun onConnectionStateChanged(address: String, state: Int) {
        Log.i(TAG, "onConnectionStateChanged $address state=$state")
        var becameConnected = false
        synchronized(lock) {
            val activeBefore = registry.activeAddress
            becameConnected = registry.updateConnectionState(address, state)
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
                // 连接已断：之前暂存的报告作废（避免连上别的设备时补发陈旧输入）
                clearPendingReport(address)
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
        // 连接刚建立（系统真值）：把连接前暂存的报告补发出去（A2）
        if (becameConnected) flushPendingReport(address)
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
            clearPendingReport(address)
        }
        statusListener.onHostDisconnected(address)
        scheduleReconnect(address)
        publishConnectedHosts()
    }

    // ============================================================ 内部实现

    /**
     * 下发 registerApp 并启动「回调缺席」看门狗 + 自愈。
     *
     * 实测（小米 21091116UC / Android 13）两个 ROM 怪癖：
     * 1) registerApp 返回 false 但注册实际成功——返回值不可信，真结果只看
     *    onAppStatusChanged 回调；
     * 2) App 死亡重启后的首次注册被服务端静默弹回（旧注册残留占用），此后
     *    永远 rejected 且无回调，直到重启手机。
     * 因此以「回调是否到达」为准；缺席则 unregisterApp() 清残留后重注册
     * （最多 [REGISTRATION_MAX_RECOVERIES] 轮），全部失败才上报注册被拒。
     */
    private fun tryRegisterApp() {
        val sdp = HidSdpRecord(
            name = SDP_RECORD_NAME,
            description = SDP_RECORD_DESCRIPTION,
            provider = SDP_RECORD_PROVIDER,
            subclass = BluetoothHidDevice.SUBCLASS1_COMBO.toInt(),
            descriptors = HidReportDescriptor.bytes,
        )
        val qos = HidQosSettings.interactive()
        val accepted = gateway.registerApp(sdp, qos, qos, this)
        Log.i(TAG, "registerApp dispatched (returned=$accepted attempt=${registrationAttempts + 1})")
        registrationWatchdogCancel?.invoke()
        registrationWatchdogCancel = reconnectScheduler.schedule(
            REGISTRATION_WATCHDOG_KEY,
            REGISTRATION_WATCHDOG_MS,
        ) {
            if (synchronized(lock) { isAppRegistered }) return@schedule
            registrationAttempts += 1
            if (registrationAttempts <= REGISTRATION_MAX_RECOVERIES) {
                // 清掉服务端残留的旧注册（同一 UID 的 unregisterApp 才能释放）；
                // 间隔要盖过服务端异步处理（太短会让迟到的 unregister 反杀新注册），
                // 且重试前重建 profile 代理——旧 binder 连接可能已被残留状态拖死
                Log.w(TAG, "registerApp 回调缺席，自愈第 $registrationAttempts 轮：unregisterApp + 重建代理 + 重注册")
                gateway.unregisterApp()
                reconnectScheduler.schedule(REGISTRATION_RETRY_KEY, REGISTRATION_RETRY_DELAY_MS) {
                    if (synchronized(lock) { isAppRegistered }) return@schedule
                    gateway.close()
                    gateway.open(
                        onReady = { tryRegisterApp() },
                        onFailed = { reason -> statusListener.onUnavailable(reason) },
                    )
                }
            } else {
                Log.w(TAG, "registerApp 自愈 $REGISTRATION_MAX_RECOVERIES 轮仍无回调")
                statusListener.onUnavailable(HidUnavailableReason.REGISTRATION_REJECTED)
            }
        }
    }

    private fun onProfileReady() {
        refreshBondedDevices()
        bondEventSource.start(::onBondEvent)
        tryRegisterApp()
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
                    // 设备已解除配对：它不可能再被连上，暂存的报告作废
                    clearPendingReport(address)
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
                pendingReport = null
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
            // 系统真值说它已连接：把连接前暂存的报告补发出去
            flushPendingReport(host.address)
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
                    // 系统真值说它已连接：把连接前暂存的报告补发出去
                    flushPendingReport(address)
                }
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
                    // 退避重试也是「主动连接」：让 UI 持续显示「连接中…」
                    statusListener.onHostConnecting(address)
                    if (!gateway.connect(address)) {
                        // connect 命令下发失败：继续退避重试
                        scheduleReconnect(address)
                    }
                }
            }
        }
    }

    /**
     * 发送到当前控制目标（A1/A2）。
     *
     * - 没有目标：通知 UI（目标为空），返回 false；
     * - 目标未连接：**暂存本次报告**并发起主动连接（A2），连接成功后由
     *   [flushPendingReport] 补发；一次性事件（consumer 媒体键）不暂存；
     * - 已连接：直接 [HidProfileGateway.sendReport]，并**打印返回值**（A1：
     *   之前静默丢弃返回值，真机上无法判断报告是否真的发出去了）。
     */
    internal fun sendToActive(reportId: Int, data: ByteArray): Boolean {
        val target = synchronized(lock) {
            val preferred = registry.preferredTarget()
            if (preferred == null) {
                statusListener.onActiveDeviceChanged(null)
                return false
            }
            if (registry.isConnected(preferred)) preferred else null
        }
        if (target == null) {
            // 目标未连接：暂存 + 主动连接，本次不直接丢弃（A2）
            val stash = synchronized(lock) {
                val address = registry.preferredTarget() ?: return false
                if (isStashableReport(reportId)) {
                    pendingReport = PendingReport(address, reportId, data)
                }
                address
            }
            Log.i(TAG, "sendToActive: $stash 未连接，暂存 report=$reportId 并主动连接")
            connectHost(stash)
            return false
        }
        val accepted = gateway.sendReport(target, reportId, data)
        // A1：sendReport 的返回值此前被静默丢弃——它是「命令是否下发成功」的唯一信号，
        // 连接半开 / 对端已断时返回 false，不打印就无法在 logcat 里定位「按键没反应」
        Log.i(TAG, "gateway.sendReport($target, report=$reportId, size=${data.size}) = $accepted")
        return accepted
    }

    /** 状态型报告（键盘 / 鼠标）可暂存补发；一次性事件（媒体键）不暂存。 */
    private fun isStashableReport(reportId: Int): Boolean =
        reportId == HidReportFactory.REPORT_ID_KEYBOARD ||
            reportId == HidReportFactory.REPORT_ID_MOUSE ||
            reportId == HidReportFactory.REPORT_ID_MOUSE_PAN

    /**
     * 连接建立后补发暂存的报告（A2）。
     *
     * 在 [onConnectionStateChanged]（系统回调）与 [refreshConnectedHosts]
     * （逐地址 getConnectionState 兜底）两条真值通道上都会调用；补发的是
     * **最新**的键鼠状态快照，不是陈旧的单次按键。
     */
    private fun flushPendingReport(address: String) {
        val pending = synchronized(lock) {
            val report = pendingReport
            if (report == null ||
                !report.address.equals(address, ignoreCase = true) ||
                !registry.isConnected(address)
            ) {
                return
            }
            pendingReport = null
            report
        }
        Log.i(TAG, "flushPendingReport → $address report=${pending.reportId}")
        gateway.sendReport(address, pending.reportId, pending.data)
    }

    /** 作废某个地址的暂存报告（断开 / 解除配对 / 注销时调用）。 */
    private fun clearPendingReport(address: String) {
        synchronized(lock) {
            if (pendingReport?.address.equals(address, ignoreCase = true)) {
                pendingReport = null
            }
        }
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
        /** 注册看门狗：回调缺席判定窗口（ms）。 */
        private const val REGISTRATION_WATCHDOG_MS = 2_000L

        /** 自愈重注册前的静默间隔（ms）。 */
        private const val REGISTRATION_RETRY_DELAY_MS = 2_000L

        /** 自愈最大轮数。 */
        private const val REGISTRATION_MAX_RECOVERIES = 3

        /** reconnectScheduler 的看门狗键。 */
        private const val REGISTRATION_WATCHDOG_KEY = "#registration-watchdog"

        /** reconnectScheduler 的重注册键。 */
        private const val REGISTRATION_RETRY_KEY = "#registration-retry"

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
