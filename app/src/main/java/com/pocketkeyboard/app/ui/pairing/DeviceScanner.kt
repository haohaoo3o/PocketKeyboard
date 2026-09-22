package com.pocketkeyboard.app.ui.pairing

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.pocketkeyboard.app.hid.DevicePlatformDetector
import com.pocketkeyboard.app.hid.DevicePlatformHint
import com.pocketkeyboard.app.ui.DevicePlatform

/**
 * 扫描到的待添加设备（被控设备候选）。
 *
 * @param platform 平台线索只影响图标展示；真正决定键盘布局的平台在设备确认时选择。
 */
internal data class ScannedDevice(
    val address: String,
    val name: String,
    val platform: DevicePlatform,
)

/** 经典蓝牙扫描（inquiry）事件回调。 */
internal interface ScanListener {

    /** 扫描开始。 */
    fun onScanStarted() {}

    /** 发现一台设备（同地址重复上报时会带最新名字）。 */
    fun onDeviceFound(device: ScannedDevice) {}

    /** 扫描结束（自然结束或被取消）。 */
    fun onScanFinished() {}
}

/**
 * 「添加被控设备」的经典蓝牙扫描 + 发起配对（App 侧主动 `createBond`）。
 *
 * 角色说明：手机是 HID **外设**，传统流程是被控端在蓝牙菜单里选本机；但 App 也可以
 * **反客为主**——扫描找到被控设备后直接 `BluetoothDevice.createBond()` 发起配对，
 * 配对请求回到 App 的配对接管链路（自动应答 + App 内展示配对码），添加设备全程
 * 不离开 App。
 *
 * 权限：Android 12+ 需 `BLUETOOTH_SCAN`（manifest 已带 `neverForLocation`）与
 * `BLUETOOTH_CONNECT`；Android 11- 需定位权限才能 inquiry。缺权限时
 * [startScan] 返回 false，调用方引导授权。
 */
internal class DeviceScanner(private val context: Context) {

    private var listener: ScanListener? = null
    private var scanning = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val current = listener ?: return
            when (intent?.action) {
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    scanning = true
                    current.onScanStarted()
                }
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val scanned = device?.toScannedDevice(intent.getStringExtra(BluetoothDevice.EXTRA_NAME))
                        ?: return
                    current.onDeviceFound(scanned)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    scanning = false
                    current.onScanFinished()
                }
            }
        }
    }

    /** 开始扫描附近设备（约 12 秒）；已注册接收器的情况下可重复调用。 */
    @SuppressLint("MissingPermission")
    fun startScan(listener: ScanListener): Boolean {
        if (!hasScanPermission()) {
            Log.w(TAG, "startScan: missing scan/connect permission")
            return false
        }
        val adapter = bluetoothAdapter(context) ?: return false
        this.listener = listener
        if (!scanning) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            val registered = runCatching {
                // 必须 RECEIVER_EXPORTED：ACTION_FOUND / DISCOVERY_* 由蓝牙进程
                // （uid=1002）发出，NOT_EXPORTED 接收器会被 BroadcastQueue 拒投
                // （真机日志实证 Exported Denial），扫描结果永远到不了 UI。
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED,
                )
            }.onFailure { Log.w(TAG, "register scan receiver failed", it) }.isSuccess
            if (!registered) {
                this.listener = null
                return false
            }
        }
        return runCatching {
            // 先停掉可能残留的旧扫描，保证 ACTION_DISCOVERY_STARTED 能重新回调
            if (scanning) adapter.cancelDiscovery()
            adapter.startDiscovery()
        }.onFailure { Log.w(TAG, "startDiscovery failed", it) }.isSuccess
    }

    /** 停止扫描并注销接收器（幂等）。 */
    @SuppressLint("MissingPermission")
    fun stopScan() {
        listener = null
        scanning = false
        runCatching { context.unregisterReceiver(receiver) }
            .onFailure { Log.w(TAG, "unregister scan receiver: not registered", it) }
        runCatching { bluetoothAdapter(context)?.cancelDiscovery() }
    }

    /**
     * 发起与被控设备的配对（`BluetoothDevice.createBond()`，公开 API）。
     *
     * 成功只表示「配对流程已启动」：随后系统的 `ACTION_PAIRING_REQUEST` / bond 状态
     * 广播由 HID 层的配对接管链路处理（自动应答 + App 内展示配对码），bond 成功后
     * HID 层会自动连接。
     */
    @SuppressLint("MissingPermission")
    fun createBond(address: String): Boolean {
        if (!hasScanPermission()) return false
        val adapter = bluetoothAdapter(context) ?: return false
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return false
        if (device.bondState == BluetoothDevice.BOND_BONDED) return true
        // inquiry 与 createBond 并行会互相干扰，先停扫描
        runCatching { adapter.cancelDiscovery() }
        return runCatching { device.createBond() }
            .onFailure { Log.w(TAG, "createBond failed for $address", it) }
            .getOrDefault(false)
    }

    private fun hasScanPermission(): Boolean {
        val permissions = mutableListOf(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
        } else {
            // Android 11- 经典蓝牙 inquiry 需要定位权限
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.toScannedDevice(broadcastName: String?): ScannedDevice? {
        val address = runCatching { address }.getOrNull() ?: return null
        val name = broadcastName?.takeIf { it.isNotBlank() }
            ?: runCatching { name }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return null // 无名设备不展示（多半是手环等不可控设备，列出来只会误导）
        return ScannedDevice(
            address = address,
            name = name,
            platform = DevicePlatformDetector.detect(name, address).toUiPlatform(),
        )
    }

    private companion object {
        const val TAG = "DeviceScanner"
    }
}

/** HID 层平台线索 → UI 契约平台（仅用于扫描列表图标）。 */
private fun DevicePlatformHint.toUiPlatform(): DevicePlatform =
    when (this) {
        DevicePlatformHint.APPLE -> DevicePlatform.APPLE
        else -> DevicePlatform.OTHER
    }
