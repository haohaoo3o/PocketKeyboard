package com.pocketkeyboard.app.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executor

/**
 * `BluetoothHidDevice` profile 的系统实现（方案 A 的 Android 侧粘合层）。
 *
 * 关键 API 调用链（全部对应 AOSP `android.bluetooth.BluetoothHidDevice`，API 28+）：
 * ```
 * BluetoothManager.getAdapter()
 *   └─ BluetoothAdapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)   // 19
 *        └─ ServiceListener.onServiceConnected(profile, proxy) -> proxy as BluetoothHidDevice
 *             ├─ registerApp(BluetoothHidDeviceAppSdpSettings, inQos, outQos, Executor, Callback)
 *             ├─ unregisterApp()
 *             ├─ connect(device) / disconnect(device)
 *             ├─ sendReport(device, reportId, data)      // data 不含 Report ID
 *             ├─ replyReport(device, type, id, data)     // 响应 GET_REPORT
 *             ├─ reportError(device, error)
 *             ├─ getConnectedDevices() / getConnectionState(device)
 *             └─ Callback: onAppStatusChanged / onConnectionStateChanged / onGetReport /
 *                          onSetReport / onSetProtocol / onInterruptData / onVirtualCableUnplug
 * ```
 *
 * ## 已知平台行为（务必了解）
 * - **同一时刻只能有一个 App 注册 HID 外设**；注册后系统 HID Host 能力会被禁用，
 *   因此不用时要 `unregisterApp()`。
 * - **App 退到后台会被系统自动注销**（registerApp javadoc 明示），所以
 *   `onAppStatusChanged(registered = false)` 可能未经我方调用就出现，需要按需重新注册。
 * - 权限：`registerApp / connect / sendReport / getConnectedDevices / bondedDevices`
 *   都需要 `BLUETOOTH_CONNECT`（Android 12+ 运行时权限）。
 *
 * @param context Application Context（用于 getProfileProxy 与广播注册）。
 * @param bluetoothManager 系统蓝牙管理器，可传 null（无蓝牙硬件时降级）。
 * @param executor 回调执行器；建议与调用方同一线程（主线程）以保证状态机串行。
 */
class SystemHidProfileGateway(
    private val context: Context,
    private val bluetoothManager: BluetoothManager?,
    private val executor: Executor = HandlerExecutor(),
) : HidProfileGateway {

    private val adapter get() = bluetoothManager?.adapter

    @Volatile
    private var hidDevice: BluetoothHidDevice? = null

    @Volatile
    override var isReady: Boolean = false
        private set

    private var pendingOnReady: (() -> Unit)? = null
    private var pendingOnFailed: ((HidUnavailableReason) -> Unit)? = null
    private var openTimeout: Runnable? = null

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            synchronized(this@SystemHidProfileGateway) {
                openTimeout?.let { mainHandler.removeCallbacks(it) }
                openTimeout = null
                hidDevice = proxy as? BluetoothHidDevice
                isReady = hidDevice != null
                val ready = pendingOnReady
                val failed = pendingOnFailed
                pendingOnReady = null
                pendingOnFailed = null
                if (hidDevice == null) {
                    failed?.invoke(HidUnavailableReason.PROFILE_UNAVAILABLE)
                } else {
                    ready?.invoke()
                }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            synchronized(this@SystemHidProfileGateway) {
                hidDevice = null
                isReady = false
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun open(onReady: () -> Unit, onFailed: (HidUnavailableReason) -> Unit) {
        synchronized(this) {
            if (isReady) {
                onReady()
                return
            }
            val currentAdapter = adapter
            if (currentAdapter == null) {
                onFailed(HidUnavailableReason.BLUETOOTH_UNSUPPORTED)
                return
            }
            if (!currentAdapter.isEnabled) {
                onFailed(HidUnavailableReason.BLUETOOTH_OFF)
                return
            }
            if (missingConnectPermission()) {
                onFailed(HidUnavailableReason.MISSING_PERMISSION)
                return
            }
            pendingOnReady = onReady
            pendingOnFailed = onFailed
            // 部分 ROM 既不回调 onServiceConnected 也不回调 onServiceDisconnected，
            // 用超时兜底，避免上层一直等待。
            val timeout = Runnable {
                synchronized(this) {
                    if (!isReady) {
                        val failed = pendingOnFailed
                        pendingOnReady = null
                        pendingOnFailed = null
                        openTimeout = null
                        failed?.invoke(HidUnavailableReason.PROFILE_UNAVAILABLE)
                    }
                }
            }
            openTimeout = timeout
            mainHandler.postDelayed(timeout, PROFILE_OPEN_TIMEOUT_MS)
            try {
                currentAdapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
            } catch (e: SecurityException) {
                openTimeout?.let { mainHandler.removeCallbacks(it) }
                openTimeout = null
                pendingOnReady = null
                pendingOnFailed = null
                Log.w(TAG, "getProfileProxy denied", e)
                onFailed(HidUnavailableReason.MISSING_PERMISSION)
            } catch (e: RuntimeException) {
                openTimeout?.let { mainHandler.removeCallbacks(it) }
                openTimeout = null
                pendingOnReady = null
                pendingOnFailed = null
                Log.w(TAG, "getProfileProxy failed", e)
                onFailed(HidUnavailableReason.PROFILE_UNAVAILABLE)
            }
        }
    }

    override fun close() {
        synchronized(this) {
            openTimeout?.let { mainHandler.removeCallbacks(it) }
            openTimeout = null
            pendingOnReady = null
            pendingOnFailed = null
            val currentAdapter = adapter
            val proxy = hidDevice
            hidDevice = null
            isReady = false
            if (currentAdapter != null && proxy != null) {
                try {
                    currentAdapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "closeProfileProxy failed", e)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun registerApp(
        sdp: HidSdpRecord,
        inQos: HidQosSettings?,
        outQos: HidQosSettings?,
        callback: HidProfileCallback,
    ): Boolean {
        val device = hidDevice ?: run {
            Log.w(TAG, "registerApp: profile proxy not ready")
            return false
        }
        if (missingConnectPermission()) {
            Log.w(TAG, "registerApp: missing BLUETOOTH_CONNECT")
            return false
        }
        return try {
            val sdpSettings = BluetoothHidDeviceAppSdpSettings(
                sdp.name,
                sdp.description,
                sdp.provider,
                sdp.subclass.toByte(),
                sdp.descriptors,
            )
            device.registerApp(
                sdpSettings,
                inQos?.toAndroidSettings(),
                outQos?.toAndroidSettings(),
                executor,
                CallbackBridge(callback),
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "registerApp denied", e)
            false
        } catch (e: IllegalArgumentException) {
            // 例如描述符过长或 SDP 字段超长（name/description/provider 上限 50 字节）
            Log.w(TAG, "registerApp illegal argument", e)
            false
        } catch (e: RuntimeException) {
            Log.w(TAG, "registerApp failed", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    override fun unregisterApp(): Boolean {
        val device = hidDevice ?: return false
        if (missingConnectPermission()) return false
        return try {
            device.unregisterApp()
        } catch (e: RuntimeException) {
            Log.w(TAG, "unregisterApp failed", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    override fun connect(address: String): Boolean {
        val device = hidDevice ?: return false
        if (missingConnectPermission()) return false
        return try {
            device.connect(remoteDevice(address) ?: return false)
        } catch (e: RuntimeException) {
            Log.w(TAG, "connect failed", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    override fun disconnect(address: String): Boolean {
        val device = hidDevice ?: return false
        if (missingConnectPermission()) return false
        return try {
            device.disconnect(remoteDevice(address) ?: return false)
        } catch (e: RuntimeException) {
            Log.w(TAG, "disconnect failed", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    override fun sendReport(address: String, reportId: Int, data: ByteArray): Boolean {
        val device = hidDevice ?: return false
        if (missingConnectPermission()) return false
        return try {
            device.sendReport(remoteDevice(address) ?: return false, reportId, data)
        } catch (e: RuntimeException) {
            Log.w(TAG, "sendReport failed", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    override fun replyReport(address: String, reportType: Int, reportId: Int, data: ByteArray): Boolean {
        val device = hidDevice ?: return false
        if (missingConnectPermission()) return false
        return try {
            device.replyReport(
                remoteDevice(address) ?: return false,
                reportType.toByte(),
                reportId.toByte(),
                data,
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "replyReport failed", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    override fun reportError(address: String, error: Int): Boolean {
        val device = hidDevice ?: return false
        if (missingConnectPermission()) return false
        return try {
            device.reportError(remoteDevice(address) ?: return false, error.toByte())
        } catch (e: RuntimeException) {
            Log.w(TAG, "reportError failed", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    override fun connectedHosts(): List<HidHost> {
        val device = hidDevice ?: return emptyList()
        if (missingConnectPermission()) return emptyList()
        return try {
            device.connectedDevices.map { it.toHidHost() }
        } catch (e: RuntimeException) {
            Log.w(TAG, "getConnectedDevices failed", e)
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    override fun connectionState(address: String): Int {
        val device = hidDevice ?: return HidHostRegistry.STATE_UNKNOWN
        if (missingConnectPermission()) return HidHostRegistry.STATE_UNKNOWN
        return try {
            device.getConnectionState(
                remoteDevice(address) ?: return HidHostRegistry.STATE_UNKNOWN,
            )
        } catch (e: RuntimeException) {
            HidHostRegistry.STATE_UNKNOWN
        }
    }

    @SuppressLint("MissingPermission")
    override fun bondedHosts(): List<HidHost> {
        val currentAdapter = adapter ?: return emptyList()
        if (missingConnectPermission()) return emptyList()
        return try {
            currentAdapter.bondedDevices.map { it.toHidHost() }
        } catch (e: RuntimeException) {
            Log.w(TAG, "getBondedDevices failed", e)
            emptyList()
        }
    }

    /**
     * 设置本机蓝牙广播名（`BluetoothAdapter.setName`）。
     *
     * 这是被控设备蓝牙菜单里看到的 GAP 名（经典蓝牙下 SDP 记录名只是服务名，
     * 列表里显示的是适配器名），因此「PocketKeyboard-redmi」这类与手机系统名的
     * 区分必须靠这里。名字已一致时直接返回 true（幂等，避免无谓的适配器写操作）。
     */
    @SuppressLint("MissingPermission")
    override fun setLocalName(name: String): Boolean {
        val currentAdapter = adapter ?: return false
        if (missingConnectPermission()) return false
        return try {
            val current = currentAdapter.name
            if (current == name) return true
            currentAdapter.setName(name)
        } catch (e: RuntimeException) {
            Log.w(TAG, "setName failed", e)
            false
        }
    }

    // ---------------------------------------------------------------- 内部工具

    private fun remoteDevice(address: String): BluetoothDevice? = try {
        adapter?.getRemoteDevice(address)
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "invalid address: $address", e)
        null
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.toHidHost(): HidHost {
        val name = try {
            name
        } catch (e: SecurityException) {
            null
        }
        val bondState = try {
            when (bondState) {
                BluetoothDevice.BOND_BONDED -> HidBondState.BONDED
                BluetoothDevice.BOND_BONDING -> HidBondState.BONDING
                BluetoothDevice.BOND_NONE -> HidBondState.NONE
                else -> HidBondState.UNKNOWN
            }
        } catch (e: SecurityException) {
            HidBondState.UNKNOWN
        }
        return HidHost(address = address, name = name, bondState = bondState)
    }

    /**
     * Android 12（API 31）之前 `BLUETOOTH_CONNECT` 不是运行时权限，
     * 因此只在 API 31+ 检查，避免低版本误判为「缺少权限」。
     */
    private fun missingConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** 把 SDK 回调桥接到纯 Kotlin [HidProfileCallback]。 */
    private class CallbackBridge(private val target: HidProfileCallback) :
        BluetoothHidDevice.Callback() {

        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            target.onAppStatusChanged(pluggedDevice?.address, registered)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            target.onConnectionStateChanged(device.address, state)
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            target.onGetReport(device.address, type.toInt(), id.toInt(), bufferSize)
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            target.onSetReport(device.address, type.toInt(), id.toInt(), data)
        }

        override fun onSetProtocol(device: BluetoothDevice, protocolMode: Byte) {
            target.onSetProtocol(device.address, protocolMode.toInt())
        }

        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {
            target.onInterruptData(device.address, reportId.toInt(), data)
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            target.onVirtualCableUnplug(device.address)
        }
    }

    private fun HidQosSettings.toAndroidSettings(): BluetoothHidDeviceAppQosSettings =
        BluetoothHidDeviceAppQosSettings(
            serviceType,
            tokenRate,
            tokenBucketSize,
            peakBandwidth,
            latency,
            delayVariation,
        )

    companion object {
        private const val TAG = "HidProfileGateway"

        /** profile 代理获取超时：部分 ROM 不回调，超时后按不可用处理。 */
        private const val PROFILE_OPEN_TIMEOUT_MS = 8_000L
    }
}

/** 主线程 Executor：让 HID 回调与 UI 调用串行，状态机无需额外加锁。 */
class HandlerExecutor(private val handler: Handler = Handler(Looper.getMainLooper())) : Executor {
    override fun execute(command: Runnable) {
        handler.post(command)
    }
}
