package com.pocketkeyboard.app.hid

import com.pocketkeyboard.app.ui.DevicePlatform

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HidDeviceTransport] 状态机单元测试：全部依赖用 fake 注入，不需要 Android 设备。
 */
class HidDeviceTransportTest {

    private class SentReport(val address: String, val reportId: Int, val data: ByteArray)

    private class FakeGateway : HidProfileGateway {
        override var isReady: Boolean = false
        var openFailure: HidUnavailableReason? = null
        var registerAppAccepted: Boolean = true
        var sdpRecord: HidSdpRecord? = null
        var connectShouldFail: Boolean = false
        var sendReportShouldFail: Boolean = false
        val sentReports = mutableListOf<SentReport>()
        val replies = mutableListOf<Triple<String, Int, ByteArray>>()
        val connectCalls = mutableListOf<String>()
        val bonded = mutableListOf<HidHost>()
        val connected = mutableListOf<HidHost>()
        private var callback: HidProfileCallback? = null

        fun fireOpen() {
            isReady = true
            openFailure?.let { pendingFailed?.invoke(it); return }
            pendingReady?.invoke()
        }

        private var pendingReady: (() -> Unit)? = null
        private var pendingFailed: ((HidUnavailableReason) -> Unit)? = null

        override fun open(onReady: () -> Unit, onFailed: (HidUnavailableReason) -> Unit) {
            pendingReady = onReady
            pendingFailed = onFailed
        }

        override fun close() {
            isReady = false
        }

        override fun registerApp(
            sdp: HidSdpRecord,
            inQos: HidQosSettings?,
            outQos: HidQosSettings?,
            callback: HidProfileCallback,
        ): Boolean {
            sdpRecord = sdp
            this.callback = callback
            return registerAppAccepted
        }

        /** 自愈会反复清残留注册，统计调用次数供断言。 */
        var unregisterAppCalls = 0

        override fun unregisterApp(): Boolean {
            unregisterAppCalls += 1
            return true
        }

        override fun connect(address: String): Boolean {
            connectCalls.add(address)
            return !connectShouldFail
        }

        override fun disconnect(address: String): Boolean = true

        override fun sendReport(address: String, reportId: Int, data: ByteArray): Boolean {
            sentReports.add(SentReport(address, reportId, data))
            return !sendReportShouldFail
        }

        override fun replyReport(address: String, reportType: Int, reportId: Int, data: ByteArray): Boolean {
            replies.add(Triple(address, reportId, data))
            return true
        }

        override fun reportError(address: String, error: Int): Boolean = true

        override fun connectedHosts(): List<HidHost> = connected

        override fun connectionState(address: String): Int =
            if (connected.any { it.address == address }) HidHostRegistry.STATE_CONNECTED
            else HidHostRegistry.STATE_DISCONNECTED

        override fun bondedHosts(): List<HidHost> = bonded

        // ---- 触发 SDK 回调 ----
        fun appStatus(plugged: String?, registered: Boolean) =
            callback?.onAppStatusChanged(plugged, registered)

        fun connectionState(address: String, state: Int) =
            callback?.onConnectionStateChanged(address, state)

        fun virtualCableUnplug(address: String) = callback?.onVirtualCableUnplug(address)

        fun getReport(address: String, type: Int, id: Int, bufferSize: Int) =
            callback?.onGetReport(address, type, id, bufferSize)

        fun setReport(address: String, type: Int, id: Int, data: ByteArray) =
            callback?.onSetReport(address, type, id, data)
    }

    private class FakeBondEventSource : BondEventSource {
        var listener: BondEventListener? = null
        var started = false
        override fun start(listener: BondEventListener) {
            this.listener = listener
            started = true
        }

        override fun stop() {
            listener = null
            started = false
        }

        fun emit(event: BondEvent) {
            listener?.onBondEvent(event)
        }
    }

    private class FakeScheduler : ReconnectScheduler {
        val pending = mutableMapOf<String, Pair<Long, () -> Unit>>()
        override fun schedule(address: String, delayMillis: Long, action: () -> Unit): () -> Unit {
            pending[address] = delayMillis to action
            return { pending.remove(address) }
        }

        override fun cancel(address: String) {
            pending.remove(address)
        }

        override fun cancelAll() {
            pending.clear()
        }

        fun runPending(address: String) {
            pending.remove(address)?.second?.invoke()
        }
    }

    private class RecordingListener : HidStatusListener {
        var registered: Boolean? = null
        var paired: List<HidDeviceInfo>? = null
        val connectedHosts = mutableListOf<HidDeviceInfo>()
        val disconnectedHosts = mutableListOf<String>()
        val connecting = mutableListOf<String>()
        val connectFailed = mutableListOf<String>()
        var active: HidDeviceInfo? = null
        val bondStates = mutableListOf<Pair<String, HidBondState>>()
        var pairing: Triple<String, HidPairingVariant, Int?>? = null
        var unavailable: HidUnavailableReason? = null
        val reconnecting = mutableListOf<Pair<String, Int>>()
        var leds: Triple<Boolean, Boolean, Boolean>? = null
        var connectedAddresses: Set<String>? = null

        override fun onAppRegistrationChanged(registered: Boolean) {
            this.registered = registered
        }

        override fun onPairedDevicesChanged(devices: List<HidDeviceInfo>) {
            paired = devices
        }

        override fun onHostConnected(device: HidDeviceInfo) {
            connectedHosts.add(device)
        }

        override fun onHostDisconnected(address: String) {
            disconnectedHosts.add(address)
        }

        override fun onHostConnecting(address: String) {
            connecting.add(address)
        }

        override fun onHostConnectFailed(address: String) {
            connectFailed.add(address)
        }

        override fun onActiveDeviceChanged(device: HidDeviceInfo?) {
            active = device
        }

        override fun onBondStateChanged(address: String, state: HidBondState) {
            bondStates.add(address to state)
        }

        override fun onPairingRequest(address: String, variant: HidPairingVariant, pinOrPasskey: Int?) {
            pairing = Triple(address, variant, pinOrPasskey)
        }

        override fun onUnavailable(reason: HidUnavailableReason) {
            unavailable = reason
        }

        override fun onReconnecting(address: String, attempt: Int) {
            reconnecting.add(address to attempt)
        }

        override fun onLedStateChanged(numLock: Boolean, capsLock: Boolean, scrollLock: Boolean) {
            leds = Triple(numLock, capsLock, scrollLock)
        }

        override fun onConnectedDevicesChanged(addresses: Set<String>) {
            connectedAddresses = addresses
        }
    }

    /** 记录调用的假配对应答器（Bug 2a 接线测试用）。 */
    private class RecordingResponder : PairingResponder {
        val calls = mutableListOf<Triple<String, HidPairingVariant, Int?>>()
        var answer: PairingAnswer = PairingAnswer.PIN_ANSWERED

        override fun answer(
            address: String,
            variant: HidPairingVariant,
            passkey: Int?,
        ): PairingAnswer {
            calls.add(Triple(address, variant, passkey))
            return answer
        }
    }

    private val host = HidHost("AA:BB:CC:DD:EE:01", "iPhone", HidBondState.BONDED)
    private val host2 = HidHost("AA:BB:CC:DD:EE:02", "MacBook", HidBondState.BONDED)

    private class Harness(
        val transport: HidDeviceTransport,
        val listener: RecordingListener,
        val gateway: FakeGateway,
        val bondSource: FakeBondEventSource,
        val scheduler: FakeScheduler,
    )

    private fun harness(
        listener: RecordingListener = RecordingListener(),
        gateway: FakeGateway = FakeGateway(),
        bondSource: FakeBondEventSource = FakeBondEventSource(),
        scheduler: FakeScheduler = FakeScheduler(),
        responder: PairingResponder = NoOpPairingResponder(),
        appInForeground: () -> Boolean = { true },
    ): Harness {
        val transport = HidDeviceTransport(
            statusListener = listener,
            gateway = gateway,
            bondEventSource = bondSource,
            reconnectScheduler = scheduler,
            pairingResponder = responder,
            appInForeground = appInForeground,
        )
        return Harness(transport, listener, gateway, bondSource, scheduler)
    }

    // ---------------------------------------------------------------- 注册

    @Test
    fun `start registers hid app with combo descriptor`() {
        val h = harness()
        val transport = h.transport
        val listener = h.listener
        val gateway = h.gateway
        transport.start()
        gateway.fireOpen()
        // registerApp 是异步的：真正的注册结果由 onAppStatusChanged 回调给出，
        // fake 必须补一次 appStatus，transport 才会置上 isAppRegistered
        gateway.appStatus(null, true)

        assertNotNull(gateway.sdpRecord)
        val sdp = gateway.sdpRecord!!
        assertEquals("口袋键鼠", sdp.name)
        assertEquals("蓝牙键盘与触控板", sdp.description)
        assertEquals("口袋键鼠", sdp.provider)
        assertEquals(0xC0, sdp.subclass and 0xFF) // SUBCLASS1_COMBO
        assertArrayEquals(HidReportDescriptor.bytes, sdp.descriptors)
        assertTrue(transport.isAppRegistered)
        assertEquals(null, listener.unavailable)
    }

    @Test
    fun `start reports unavailable when profile cannot be opened`() {
        val gateway = FakeGateway().apply { openFailure = HidUnavailableReason.PROFILE_UNAVAILABLE }
        val h = harness(gateway = gateway)
        val transport = h.transport
        val listener = h.listener

        transport.start()
        gateway.fireOpen()

        assertEquals(HidUnavailableReason.PROFILE_UNAVAILABLE, listener.unavailable)
        assertFalse(transport.isAppRegistered)
    }

    @Test
    fun `register rejection without callback self-heals then reports registration rejected`() {
        val gateway = FakeGateway().apply { registerAppAccepted = false }
        val h = harness(gateway = gateway)
        val transport = h.transport
        val listener = h.listener

        transport.start()
        gateway.fireOpen()

        // 真结果只看回调：回调缺席时先自愈，而不是立刻判死（MIUI 怪癖：App 重启后的
        // 首次注册会被服务端静默弹回，unregisterApp 清残留 + 重注册才能恢复）
        assertNull(listener.unavailable)

        // 3 轮自愈：看门狗 → unregisterApp 清残留 + 重建代理 → 重注册（均不上报）
        repeat(3) {
            h.scheduler.runPending("#registration-watchdog")
            h.scheduler.runPending("#registration-retry")
            h.gateway.fireOpen()
            assertNull(listener.unavailable)
        }
        // 第 4 次看门狗到期：自愈轮数用尽 → 才上报注册被拒
        h.scheduler.runPending("#registration-watchdog")
        assertEquals(HidUnavailableReason.REGISTRATION_REJECTED, listener.unavailable)
        assertTrue(gateway.unregisterAppCalls >= 3)
    }

    @Test
    fun `registration callback arrival counts as success even when registerApp returned false`() {
        // ROM 怪癖：registerApp 返回 false 但回调说注册成功——以回调为准，不触发自愈
        val gateway = FakeGateway().apply { registerAppAccepted = false }
        val h = harness(gateway = gateway)
        h.transport.start()
        gateway.fireOpen()
        gateway.appStatus(null, true)

        assertNull(h.listener.unavailable)
        assertTrue(h.transport.isAppRegistered)
        // 看门狗已被撤销：剩余调度再跑也不会触发自愈
        h.scheduler.runPending("#registration-watchdog")
        assertNull(h.listener.unavailable)
    }

    @Test
    fun `app status change propagates to listener`() {
        val h = harness()
        val transport = h.transport
        val listener = h.listener
        val gateway = h.gateway
        transport.start()
        gateway.fireOpen()
        gateway.appStatus(null, true)

        assertEquals(true, listener.registered)
        assertTrue(transport.isAppRegistered)

        gateway.appStatus(null, false)
        assertEquals(false, listener.registered)
        assertFalse(transport.isAppRegistered)
    }

    // ---------------------------------------------------------------- 连接与多设备

    @Test
    fun `host connection selects active device and notifies`() {
        val h = harness()
        val transport = h.transport
        val listener = h.listener
        val gateway = h.gateway
        transport.start()
        gateway.fireOpen()
        // 只有已配对设备才能被 HID 连接，注册时先刷新已配对列表拿到设备名
        gateway.bonded.add(host)
        gateway.appStatus(null, true)

        gateway.connectionState(host.address, HidHostRegistry.STATE_CONNECTED)

        assertEquals(1, listener.connectedHosts.size)
        assertEquals(host.address, listener.connectedHosts.single().address)
        assertEquals(host.address, listener.active?.address)
        assertEquals(DevicePlatformHint.APPLE, listener.active?.platform)
    }

    @Test
    fun `keyboard report is routed to the active device`() {
        val h = harness()
        val transport = h.transport
        val gateway = h.gateway
        transport.start()
        gateway.fireOpen()
        gateway.appStatus(null, true)
        gateway.connectionState(host.address, HidHostRegistry.STATE_CONNECTED)

        transport.sendKeyboardReport(HidModifier.LEFT_GUI, byteArrayOf(HidUsage.KEY_A.toByte()))

        val report = gateway.sentReports.single()
        assertEquals(host.address, report.address)
        assertEquals(HidReportFactory.REPORT_ID_KEYBOARD, report.reportId)
        assertEquals(8, report.data.size)
        assertEquals(HidModifier.LEFT_GUI.toByte(), report.data[0])
        assertEquals(HidUsage.KEY_A.toByte(), report.data[2])
    }

    @Test
    fun `reports are dropped when no host is connected`() {
        val h = harness()
        val transport = h.transport
        val listener = h.listener
        val gateway = h.gateway
        transport.start()
        gateway.fireOpen()
        gateway.appStatus(null, true)

        transport.sendKeyboardReport(0, byteArrayOf(0x04))
        transport.sendConsumerUsage(HidUsage.CONSUMER_VOLUME_UP)
        transport.sendMouseMove(10, 10)
        transport.sendScroll(0, 3)
        transport.sendMouseButton(MouseButton.LEFT, true)

        assertTrue(gateway.sentReports.isEmpty())
        assertNull(listener.active)
    }

    @Test
    fun `consumer usage is sent as two byte little endian report`() {
        val h = harness()
        val transport = h.transport
        val gateway = h.gateway
        transport.start()
        gateway.fireOpen()
        gateway.appStatus(null, true)
        gateway.connectionState(host.address, HidHostRegistry.STATE_CONNECTED)

        transport.sendConsumerUsage(HidUsage.CONSUMER_PLAY_PAUSE)

        val report = gateway.sentReports.single()
        assertEquals(HidReportFactory.REPORT_ID_CONSUMER, report.reportId)
        assertArrayEquals(byteArrayOf(0xCD.toByte(), 0x00), report.data)
    }

    @Test
    fun `mouse move keeps pressed buttons for dragging`() {
        val h = harness()
        val transport = h.transport
        val gateway = h.gateway
        transport.start()
        gateway.fireOpen()
        gateway.appStatus(null, true)
        gateway.connectionState(host.address, HidHostRegistry.STATE_CONNECTED)

        transport.sendMouseButton(MouseButton.LEFT, true)
        transport.sendMouseMove(20, -10)
        transport.sendMouseButton(MouseButton.LEFT, false)
        transport.sendMouseMove(5, 5)

        assertEquals(4, gateway.sentReports.size)
        val press = gateway.sentReports[0]
        assertEquals(HidReportFactory.BUTTON_LEFT.toByte(), press.data[0])
        val drag = gateway.sentReports[1]
        assertEquals(HidReportFactory.BUTTON_LEFT.toByte(), drag.data[0])
        assertEquals(20.toByte(), drag.data[1])
        assertEquals((-10).toByte(), drag.data[2])
        val release = gateway.sentReports[2]
        assertEquals(0x00.toByte(), release.data[0])
        val afterRelease = gateway.sentReports[3]
        assertEquals(0x00.toByte(), afterRelease.data[0])
    }

    @Test
    fun `vertical scroll uses 4 byte report and horizontal uses 5 byte report`() {
        val h = harness()
        val transport = h.transport
        val gateway = h.gateway
        transport.start()
        gateway.fireOpen()
        gateway.appStatus(null, true)
        gateway.connectionState(host.address, HidHostRegistry.STATE_CONNECTED)

        transport.sendScroll(0, -2)
        assertEquals(HidReportFactory.REPORT_ID_MOUSE, gateway.sentReports.last().reportId)
        assertEquals(4, gateway.sentReports.last().data.size)

        transport.sendScroll(-3, 0)
        assertEquals(HidReportFactory.REPORT_ID_MOUSE_PAN, gateway.sentReports.last().reportId)
        assertEquals(5, gateway.sentReports.last().data.size)
        assertEquals((-3).toByte(), gateway.sentReports.last().data[4])
    }

    @Test
    fun `second host connection does not steal the active device`() {
        val h = harness()
        val transport = h.transport
        val listener = h.listener
        val gateway = h.gateway
        transport.start()
        gateway.fireOpen()
        gateway.appStatus(null, true)
        gateway.connectionState(host.address, HidHostRegistry.STATE_CONNECTED)
        gateway.connectionState(host2.address, HidHostRegistry.STATE_CONNECTED)

        assertEquals(host.address, listener.active?.address)
        assertEquals(2, listener.connectedHosts.size)

        transport.setActiveDevice(host2.address)
        assertEquals(host2.address, listener.active?.address)

        // 切换目标后再发的报告必须打到新目标上（这才是切换生效的证据）
        transport.sendKeyboardReport(0, byteArrayOf(0x04))
        val report = gateway.sentReports.last()
        assertEquals(host2.address, report.address)
    }

    @Test
    fun `cycle active device rotates between connected hosts`() {
        val h = harness()
        val transport = h.transport
        val listener = h.listener
        val gateway = h.gateway
        transport.start()
        gateway.fireOpen()
        gateway.appStatus(null, true)
        gateway.connectionState(host.address, HidHostRegistry.STATE_CONNECTED)
        gateway.connectionState(host2.address, HidHostRegistry.STATE_CONNECTED)

        transport.cycleActiveDevice()

        assertEquals(host2.address, listener.active?.address)
    }

    // ---------------------------------------------------------------- 断开重连

    @Test
    fun `disconnect schedules an exponential backoff reconnect`() {
        val h = harness()
        val transport = h.transport
        val listener = h.listener
        val gateway = h.gateway
        val scheduler = h.scheduler
        transport.start()
        // 主机必须是已配对（bonded）的：断开重连的触发条件就是「已配对的主机断开」。
        // 要在 appStatus（触发 refreshBondedDevices）之前放好，registry 才会记下 BONDED
        gateway.bonded.add(host)
        gateway.fireOpen()
        gateway.appStatus(null, true)
        gateway.connectionState(host.address, HidHostRegistry.STATE_CONNECTED)

        gateway.connectionState(host.address, HidHostRegistry.STATE_DISCONNECTED)

        assertEquals(listOf(host.address), listener.disconnectedHosts)
        assertEquals(ReconnectPolicy.delayFor(1), scheduler.pending[host.address]?.first)

        scheduler.runPending(host.address)
        assertTrue(gateway.connectCalls.contains(host.address))
    }

    @Test
    fun `virtual cable unplug notifies and schedules reconnect`() {
        val h = harness()
        val transport = h.transport
        val listener = h.listener
        val gateway = h.gateway
        val scheduler = h.scheduler
        transport.start()
        gateway.fireOpen()
        gateway.appStatus(null, true)
        gateway.connectionState(host.address, HidHostRegistry.STATE_CONNECTED)

        gateway.virtualCableUnplug(host.address)

        assertEquals(listOf(host.address), listener.disconnectedHosts)
        assertTrue(scheduler.pending.containsKey(host.address))
    }

    @Test
    fun `stop cancels pending reconnects and unregisters the app`() {
        val h = harness()
        val transport = h.transport
        val gateway = h.gateway
        val bondSource = h.bondSource
        val scheduler = h.scheduler
        transport.start()
        // 已配对主机断开才会排重连；bonded 要在 appStatus 之前放好
        gateway.bonded.add(host)
        gateway.fireOpen()
        gateway.appStatus(null, true)
        gateway.connectionState(host.address, HidHostRegistry.STATE_CONNECTED)
        gateway.connectionState(host.address, HidHostRegistry.STATE_DISCONNECTED)
        assertTrue(scheduler.pending.isNotEmpty())

        transport.stop()

        assertTrue(scheduler.pending.isEmpty())
        assertFalse(bondSource.started)
        assertFalse(transport.isAppRegistered)
    }

    // ---------------------------------------------------------------- 配对桥接

    @Test
    fun `bonding updates paired list and connects the new host`() {
        val h = harness()
        val transport = h.transport
        val listener = h.listener
        val gateway = h.gateway
        val bondSource = h.bondSource
        transport.start()
        gateway.fireOpen()
        gateway.appStatus(null, true)
        gateway.bonded.add(host)

        bondSource.emit(BondEvent.BondStateChanged(host.address, HidBondState.BONDING))
        assertEquals(HidBondState.BONDING, listener.bondStates.single().second)

        bondSource.emit(BondEvent.BondStateChanged(host.address, HidBondState.BONDED))

        assertNotNull(listener.paired)
        assertEquals(1, listener.paired!!.size)
        assertEquals("iPhone", listener.paired!!.single().name)
        // 传输层给的是 hid 包的 DevicePlatformHint；转成 UI 契约的 DevicePlatform
        // 是 MainViewModelStatusBridge 的职责，不在这里断言
        assertEquals(DevicePlatformHint.APPLE, listener.paired!!.single().platform)
        assertTrue(gateway.connectCalls.contains(host.address))
    }

    @Test
    fun `pairing request with passkey is bridged with the 6 digit code`() {
        val h = harness()
        val transport = h.transport
        val listener = h.listener
        val bondSource = h.bondSource
        transport.start()
        // bond 广播监听是在 profile 就绪后（onProfileReady）注册的，先走这一步
        h.gateway.fireOpen()
        bondSource.emit(
            BondEvent.PairingRequest(host.address, HidPairingVariant.PASSKEY_CONFIRMATION, 123456),
        )

        val event = listener.pairing
        assertNotNull(event)
        assertEquals(HidPairingVariant.PASSKEY_CONFIRMATION, event!!.second)
        assertEquals(123456, event.third)
    }

    @Test
    fun `just works pairing has no pin code`() {
        val h = harness()
        val transport = h.transport
        val listener = h.listener
        val bondSource = h.bondSource
        transport.start()
        bondSource.emit(BondEvent.PairingRequest(host.address, HidPairingVariant.CONSENT, null))

        assertNull(listener.pairing?.third)
    }

    // ---------------------------------------------------------------- 配对自动应答（Bug 2a）

    @Test
    fun `pairing request is auto answered when hid app is registered and app in foreground`() {
        val responder = RecordingResponder()
        val h = harness(responder = responder)
        val bondSource = h.bondSource
        h.transport.start()
        h.gateway.fireOpen()
        // 注册成功后才允许自动应答（registerApp 本身要求 App 在前台）
        h.gateway.appStatus(null, true)

        bondSource.emit(
            BondEvent.PairingRequest(host.address, HidPairingVariant.PASSKEY_ENTRY, null),
        )

        assertEquals(1, responder.calls.size)
        assertEquals(host.address, responder.calls.single().first)
        assertEquals(HidPairingVariant.PASSKEY_ENTRY, responder.calls.single().second)
        // 应答结果不影响广播继续上报给 UI（bridge 仍据此刷新配对码）
        assertNotNull(h.listener.pairing)
    }

    @Test
    fun `pairing request is not auto answered before hid app registration`() {
        val responder = RecordingResponder()
        val h = harness(responder = responder)
        h.transport.start()
        h.gateway.fireOpen()

        h.bondSource.emit(
            BondEvent.PairingRequest(host.address, HidPairingVariant.CONSENT, null),
        )

        assertTrue(responder.calls.isEmpty())
        // 不代劳 ≠ 不桥接：UI 仍然收到配对请求
        assertNotNull(h.listener.pairing)
    }

    @Test
    fun `pairing request is not auto answered when app is in background`() {
        val responder = RecordingResponder()
        val h = harness(responder = responder, appInForeground = { false })
        h.transport.start()
        h.gateway.fireOpen()
        h.gateway.appStatus(null, true)

        h.bondSource.emit(
            BondEvent.PairingRequest(host.address, HidPairingVariant.PASSKEY_CONFIRMATION, 123456),
        )

        assertTrue(responder.calls.isEmpty())
        assertNotNull(h.listener.pairing)
    }

    // ---------------------------------------------------------------- 已连接对端快照（Bug 2b）

    @Test
    fun `connected hosts snapshot is published on connect and disconnect`() {
        val h = harness()
        val transport = h.transport
        val listener = h.listener
        val gateway = h.gateway
        transport.start()
        gateway.fireOpen()
        gateway.bonded.add(host)
        gateway.appStatus(null, true)
        assertEquals(emptySet<String>(), listener.connectedAddresses)

        gateway.connectionState(host.address, HidHostRegistry.STATE_CONNECTED)
        assertEquals(setOf(host.address), listener.connectedAddresses)

        gateway.connectionState(host.address, HidHostRegistry.STATE_DISCONNECTED)
        assertEquals(emptySet<String>(), listener.connectedAddresses)
    }

    @Test
    fun `stop clears the connected hosts snapshot`() {
        val h = harness()
        val transport = h.transport
        val listener = h.listener
        val gateway = h.gateway
        transport.start()
        gateway.fireOpen()
        gateway.bonded.add(host)
        gateway.appStatus(null, true)
        gateway.connectionState(host.address, HidHostRegistry.STATE_CONNECTED)
        assertEquals(setOf(host.address), listener.connectedAddresses)

        transport.stop()

        assertEquals(emptySet<String>(), listener.connectedAddresses)
    }

    // ---------------------------------------------------------------- A1：连接状态只认系统真值

    @Test
    fun `plugged device is not registered as connected`() {
        val h = harness()
        val transport = h.transport
        val listener = h.listener
        val gateway = h.gateway
        transport.start()
        // plugged 设备通常同时是已配对设备（注册时刷新 bonded 列表会拿到名字）
        gateway.bonded.add(host)
        gateway.fireOpen()
        // MIUI 实机现象：plugged 非空，但 getConnectedDevices() 空、
        // getConnectionState() = DISCONNECTED —— 连接并没有在 profile 层建立
        gateway.appStatus(host.address, true)

        // A1：plugged 不再写 registry 的 connected（这就是此前的假阳性来源）
        assertFalse(transport.registry.isConnected(host.address))
        assertEquals(emptySet<String>(), listener.connectedAddresses)
        // plugged 只用于自动选定控制目标
        assertEquals(host.address, transport.registry.activeAddress)
        assertEquals(host.address, listener.active?.address)
    }

    @Test
    fun `registration auto connects the plugged device once`() {
        val h = harness()
        val transport = h.transport
        val listener = h.listener
        val gateway = h.gateway
        transport.start()
        gateway.bonded.add(host)
        gateway.fireOpen()
        gateway.appStatus(host.address, true)

        // A2(c)：注册成功 → 对 plugged 线索主动 connectHost 一次，UI 进入「连接中」
        assertEquals(listOf(host.address), gateway.connectCalls)
        assertEquals(listOf(host.address), listener.connecting)
        assertEquals(HidHostRegistry.STATE_CONNECTING, transport.registry.entry(host.address)?.connectionState)
        // 命令已接受（fake 默认成功）：不应出现失败提示
        assertEquals(emptyList<String>(), listener.connectFailed)
    }

    @Test
    fun `registration does not connect when there is no target at all`() {
        val h = harness()
        val transport = h.transport
        val gateway = h.gateway
        transport.start()
        gateway.fireOpen()
        // 没有任何 plugged 线索、没有已连接主机、没有已选目标 → 无可连接对象
        gateway.appStatus(null, true)

        assertTrue(gateway.connectCalls.isEmpty())
        assertTrue(h.listener.connecting.isEmpty())
    }

    @Test
    fun `connected device at registration is confirmed as the active target`() {
        val h = harness()
        val transport = h.transport
        val listener = h.listener
        val gateway = h.gateway
        transport.start()
        gateway.fireOpen()
        gateway.bonded.add(host)
        gateway.appStatus(null, true)
        // 注册前系统已建立连接：fallback 的 getConnectionState 兜底应认它
        gateway.connected.add(host)
        gateway.appStatus(null, true)

        assertTrue(transport.registry.isConnected(host.address))
        assertEquals(setOf(host.address), listener.connectedAddresses)
        // 已连接的目标不重复发起连接
        assertTrue(gateway.connectCalls.isEmpty())
        assertEquals(host.address, listener.active?.address)
    }

    // ---------------------------------------------------------------- A1：sendReport 返回值可见

    @Test
    fun `gateway send report result is propagated to the caller`() {
        val gateway = FakeGateway().apply { sendReportShouldFail = true }
        val h = harness(gateway = gateway)
        val transport = h.transport
        transport.start()
        gateway.fireOpen()
        gateway.appStatus(null, true)
        gateway.connectionState(host.address, HidHostRegistry.STATE_CONNECTED)

        // A1：sendToActive 必须把 gateway.sendReport 的返回值透传出来（日志与调用方
        // 都据此判断「命令是否真的下发」），而不是静默吞掉
        val sent = transport.sendToActive(
            HidReportFactory.REPORT_ID_KEYBOARD,
            byteArrayOf(0, 0, HidUsage.KEY_A.toByte()),
        )

        assertFalse(sent)
        // 即使失败也真的下发了（fake 记录了调用），与「未连接直接丢弃」区分开
        assertEquals(1, gateway.sentReports.size)

        gateway.sendReportShouldFail = false
        assertTrue(
            transport.sendToActive(
                HidReportFactory.REPORT_ID_KEYBOARD,
                byteArrayOf(0, 0, HidUsage.KEY_Z.toByte()),
            ),
        )
    }

    // ---------------------------------------------------------------- A2：未连接暂存 → 连接后补发

    @Test
    fun `report is stashed while not connected and flushed after connect`() {
        val h = harness()
        val transport = h.transport
        val listener = h.listener
        val gateway = h.gateway
        transport.start()
        gateway.bonded.add(host)
        gateway.fireOpen()
        gateway.appStatus(null, true)

        // 选中目标但连接还没建立：报告暂存 + 主动连接，不直接丢弃
        transport.setActiveDevice(host.address)
        transport.sendKeyboardReport(0, byteArrayOf(HidUsage.KEY_A.toByte()))

        assertTrue(gateway.sentReports.isEmpty())
        assertEquals(listOf(host.address), listener.connecting)
        assertEquals(1, gateway.connectCalls.size)

        // 连接建立（系统真值）→ 暂存的报告补发到目标
        gateway.connectionState(host.address, HidHostRegistry.STATE_CONNECTED)

        assertEquals(1, gateway.sentReports.size)
        val flushed = gateway.sentReports.single()
        assertEquals(host.address, flushed.address)
        assertEquals(HidReportFactory.REPORT_ID_KEYBOARD, flushed.reportId)
        assertEquals(HidUsage.KEY_A.toByte(), flushed.data[2])
    }

    @Test
    fun `newer reports overwrite the stash so the flush sends the latest state`() {
        val h = harness()
        val transport = h.transport
        val gateway = h.gateway
        transport.start()
        gateway.bonded.add(host)
        gateway.fireOpen()
        gateway.appStatus(null, true)

        transport.setActiveDevice(host.address)
        transport.sendKeyboardReport(0, byteArrayOf(HidUsage.KEY_A.toByte()))
        transport.sendKeyboardReport(0, byteArrayOf(HidUsage.KEY_Z.toByte()))

        gateway.connectionState(host.address, HidHostRegistry.STATE_CONNECTED)

        // 补发的是最新一次报告（B），不是陈旧的 A
        assertEquals(1, gateway.sentReports.size)
        assertEquals(HidUsage.KEY_Z.toByte(), gateway.sentReports.single().data[2])
    }

    @Test
    fun `one shot consumer usage is dropped instead of stashed`() {
        val h = harness()
        val transport = h.transport
        val gateway = h.gateway
        transport.start()
        gateway.bonded.add(host)
        gateway.fireOpen()
        gateway.appStatus(null, true)

        transport.setActiveDevice(host.address)
        transport.sendConsumerUsage(HidUsage.CONSUMER_VOLUME_UP)

        // 一次性事件不暂存：连接后补发会造成多余的音量跳变
        gateway.connectionState(host.address, HidHostRegistry.STATE_CONNECTED)

        assertTrue(gateway.sentReports.isEmpty())
    }

    @Test
    fun `stashed report is discarded when the host disconnects before connecting`() {
        val h = harness()
        val transport = h.transport
        val gateway = h.gateway
        val scheduler = h.scheduler
        transport.start()
        gateway.bonded.add(host)
        gateway.fireOpen()
        gateway.appStatus(null, true)

        transport.setActiveDevice(host.address)
        transport.sendKeyboardReport(0, byteArrayOf(HidUsage.KEY_A.toByte()))
        // 连接成功前又断了（主机侧拒绝 / 抢在连接前断开）：暂存的报告作废
        gateway.connectionState(host.address, HidHostRegistry.STATE_DISCONNECTED)
        // 断开会排退避重试；这里验证的不是重连，而是暂存作废
        assertTrue(scheduler.pending.containsKey(host.address))

        // 之后再连上也不应补发连接前就已经作废的报告
        gateway.connectionState(host.address, HidHostRegistry.STATE_CONNECTED)

        assertTrue(gateway.sentReports.isEmpty())
        // 连接成功后重试调度被取消
        assertFalse(scheduler.pending.containsKey(host.address))
    }

    @Test
    fun `flush also happens when the fallback state query sees the host connected`() {
        val h = harness()
        val transport = h.transport
        val gateway = h.gateway
        transport.start()
        gateway.bonded.add(host)
        gateway.fireOpen()
        gateway.appStatus(null, true)

        transport.setActiveDevice(host.address)
        transport.sendKeyboardReport(0, byteArrayOf(HidUsage.KEY_A.toByte()))
        assertTrue(gateway.sentReports.isEmpty())

        // 没有 onConnectionStateChanged，只有 getConnectedDevices() / 逐地址
        // getConnectionState 兜底说已连接（再走一遍注册路径即可触发）
        gateway.connected.add(host)
        gateway.appStatus(null, true)

        assertTrue(transport.registry.isConnected(host.address))
        assertEquals(1, gateway.sentReports.size)
        assertEquals(host.address, gateway.sentReports.single().address)
    }

    // ---------------------------------------------------------------- A2：连接失败可见 + 不重复发起

    @Test
    fun `failed connect reports failure and schedules a reconnect`() {
        val gateway = FakeGateway().apply { connectShouldFail = true }
        val h = harness(gateway = gateway)
        val transport = h.transport
        val listener = h.listener
        val scheduler = h.scheduler
        transport.start()
        gateway.fireOpen()
        gateway.appStatus(null, true)

        transport.connectHost(host.address)

        assertEquals(listOf(host.address), listener.connectFailed)
        assertEquals(ReconnectPolicy.delayFor(1), scheduler.pending[host.address]?.first)
        assertEquals(
            HidHostRegistry.STATE_DISCONNECTED,
            transport.registry.entry(host.address)?.connectionState,
        )
    }

    @Test
    fun `repeated connect host does not re-initiate while already connecting`() {
        val h = harness()
        val transport = h.transport
        val listener = h.listener
        val gateway = h.gateway
        transport.start()
        gateway.fireOpen()
        gateway.appStatus(null, true)

        transport.connectHost(host.address)
        transport.connectHost(host.address)

        assertEquals(1, gateway.connectCalls.size)
        // 第二次只让 UI 保持「连接中」，不再下一次 connect 命令
        assertEquals(listOf(host.address, host.address), listener.connecting)
        assertEquals(emptyList<String>(), listener.connectFailed)
    }

    @Test
    fun `bond removal drops the device from the paired list`() {
        val h = harness()
        val transport = h.transport
        val listener = h.listener
        val gateway = h.gateway
        val bondSource = h.bondSource
        transport.start()
        gateway.fireOpen()
        gateway.appStatus(null, true)
        gateway.bonded.add(host)
        bondSource.emit(BondEvent.BondStateChanged(host.address, HidBondState.BONDED))
        assertEquals(1, listener.paired!!.size)

        bondSource.emit(BondEvent.BondStateChanged(host.address, HidBondState.NONE))

        assertEquals(0, listener.paired!!.size)
        assertNull(listener.active)
    }

    @Test
    fun `bluetooth turning off reports unavailable and clears active device`() {
        val h = harness()
        val transport = h.transport
        val listener = h.listener
        val gateway = h.gateway
        val bondSource = h.bondSource
        transport.start()
        gateway.fireOpen()
        gateway.appStatus(null, true)
        gateway.connectionState(host.address, HidHostRegistry.STATE_CONNECTED)

        bondSource.emit(BondEvent.AdapterStateChanged(false))

        assertEquals(HidUnavailableReason.BLUETOOTH_OFF, listener.unavailable)
        assertNull(listener.active)
        assertFalse(transport.isAppRegistered)
    }

    // ---------------------------------------------------------------- 报告交互

    @Test
    fun `get report replies with the last keyboard report`() {
        val h = harness()
        val transport = h.transport
        val gateway = h.gateway
        transport.start()
        gateway.fireOpen()
        gateway.appStatus(null, true)
        gateway.connectionState(host.address, HidHostRegistry.STATE_CONNECTED)
        transport.sendKeyboardReport(HidModifier.LEFT_SHIFT, byteArrayOf(0x04))

        gateway.getReport(
            host.address,
            android.bluetooth.BluetoothHidDevice.REPORT_TYPE_INPUT.toInt(),
            HidReportFactory.REPORT_ID_KEYBOARD,
            8,
        )

        val reply = gateway.replies.single()
        assertEquals(host.address, reply.first)
        assertEquals(HidReportFactory.REPORT_ID_KEYBOARD, reply.second)
        assertEquals(8, reply.third.size)
        assertEquals(HidModifier.LEFT_SHIFT.toByte(), reply.third[0])
        assertEquals(0x04.toByte(), reply.third[2])
    }

    @Test
    fun `led output report is parsed into led state`() {
        val h = harness()
        val transport = h.transport
        val listener = h.listener
        val gateway = h.gateway
        transport.start()
        gateway.fireOpen()
        gateway.appStatus(null, true)

        gateway.setReport(
            host.address,
            android.bluetooth.BluetoothHidDevice.REPORT_TYPE_OUTPUT.toInt(),
            HidReportFactory.REPORT_ID_KEYBOARD,
            byteArrayOf(0x02), // Caps Lock
        )

        assertEquals(Triple(false, true, false), listener.leds)
        assertEquals(0x02, transport.ledState)
    }
}
