package com.pocketkeyboard.app.ui.pairing

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import com.pocketkeyboard.app.ui.DevicePlatform
import com.pocketkeyboard.app.ui.PairedDevice

/** 本机蓝牙在配对页需要用到的本地信息。 */
internal data class LocalBluetoothState(
    /** 本机蓝牙名称（被控设备在系统蓝牙菜单里看到的名字），null 表示尚未取到。 */
    val name: String? = null,
    /** 本机是否具备蓝牙硬件。 */
    val available: Boolean = true,
    /** 运行时蓝牙权限是否已授予（Android 12+ 需要 BLUETOOTH_CONNECT）。 */
    val permissionGranted: Boolean = true,
    /** 系统已配对设备种子列表（平台信息来自 DataStore，未记录时按「其他」处理）。 */
    val devices: List<PairedDevice> = emptyList(),
)

/** 唤起系统「可被发现」授权时的持续时长（秒）。 */
internal const val DISCOVERABLE_DURATION_SECONDS: Int = 300

/** 本机蓝牙适配器，硬件不支持时为 null。 */
internal fun bluetoothAdapter(context: Context): BluetoothAdapter? =
    (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

/**
 * 读取本机蓝牙名称 / 已配对设备所需的运行时权限。
 *
 * Android 12+ 需要 BLUETOOTH_CONNECT；Android 11 及以下的 BLUETOOTH 是安装期权限，
 * 已配对设备列表本身不需要定位权限，因此这里不额外申请。
 */
internal fun bluetoothReadPermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        emptyList()
    }

/** 读取本机蓝牙信息所需的运行时权限是否已全部授予。 */
internal fun hasBluetoothReadPermission(context: Context): Boolean =
    bluetoothReadPermissions().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

/**
 * 采集本机蓝牙名称与系统已配对设备。
 *
 * [platforms] 为 DataStore 中已持久化的平台映射，用于给种子列表标注平台；
 * 未记录过的设备先按「其他」处理，真正连接时再由平台选择 Dialog 询问。
 */
// 权限已由 hasBluetoothReadPermission 前置检查 + runCatching 双重防护，
// lint 的流分析看不穿自定义检查函数，这里显式声明
@SuppressLint("MissingPermission")
internal fun loadLocalBluetooth(
    context: Context,
    platforms: Map<String, DevicePlatform>,
): LocalBluetoothState {
    val adapter = bluetoothAdapter(context) ?: return LocalBluetoothState(available = false)
    if (!hasBluetoothReadPermission(context)) {
        return LocalBluetoothState(permissionGranted = false)
    }
    // adapter.name / adapter.bondedDevices 在未授权时会抛 SecurityException
    val name = runCatching { adapter.name }.getOrNull()?.takeIf { it.isNotBlank() }
    val bonded = runCatching { adapter.bondedDevices }.getOrNull().orEmpty()
        .mapNotNull { device ->
            val deviceName = runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val address = runCatching { device.address }.getOrNull() ?: return@mapNotNull null
            PairedDevice(
                address = address,
                name = deviceName,
                platform = platforms[address] ?: DevicePlatform.OTHER,
            )
        }
        .sortedBy { it.name }
    return LocalBluetoothState(name = name, devices = bonded)
}

/**
 * 请求系统把本机设为可被发现，使被控设备能在系统蓝牙菜单里找到本设备。
 *
 * 走系统 ACTION_REQUEST_DISCOVERABLE 授权弹窗，用户同意后返回结果里带有实际授权秒数。
 */
internal fun requestDiscoverable(
    context: Context,
    launcher: ActivityResultLauncher<Intent>,
) {
    val adapter = bluetoothAdapter(context) ?: return
    if (adapter.state != BluetoothAdapter.STATE_ON) return
    val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
        putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_DURATION_SECONDS)
    }
    runCatching { launcher.launch(intent) }
}
