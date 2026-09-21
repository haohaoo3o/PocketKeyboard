package com.pocketkeyboard.app.hid

import com.pocketkeyboard.app.ui.DevicePlatform

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

        override fun unregisterApp(): Boolean = true

        override fun connect(address: String): Boolean {
            connectCalls.add(address)
            return !connectShouldFail
        }

        override fun disconnect(address: String): Boolean = true

        override fun sendReport(address: String, reportId: Int, data: ByteArray): Boolean {
            sentReports.add(SentReport(address, reportId, data))
            return true
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
        var active: HidDeviceInfo? = null
        val bondStates = mutableListOf<Pair<String, HidBondState>>()
        var pairing: Triple<String, HidPairingVariant, Int?>? = null
        var unavailable: HidUnavailableReason? = null
        val reconnecting = mutableListOf<Pair<String, Int>>()
        var leds: Triple<Boolean, Boolean, Boolean>? = null

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
    ): Harness {
        val transport = HidDeviceTransport(
            statusListener = listener,
            gateway = gateway,
            bondEventSource = bondSource,
            reconnectScheduler = scheduler,
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
    fun `register app rejection is reported as registration rejected`() {
        val gateway = FakeGateway().apply { registerAppAccepted = false }
        val h = harness(gateway = gateway)
        val transport = h.transport
        val listener = h.listener

        transport.start()
        gateway.fireOpen()

        assertEquals(HidUnavailableReason.REGISTRATION_REJECTED, listener.unavailable)
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
