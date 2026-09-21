package com.pocketkeyboard.app.hid

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * 解除系统配对（Bug 3 的删除动作）。
 *
 * `BluetoothDevice.removeBond()` 是隐藏 API（公开 SDK 不存在），只能反射调用；
 * 反射风险与 [HiddenBluetoothApi] 中说明的一致，失败时返回 false 由 UI 提示，
 * 绝不向上抛异常。
 */
interface BondRemover {

    /**
     * 解除某个地址的系统 bond。
     *
     * @return true 表示 `removeBond` 命令已成功下发；**最终结果以系统
     *     `ACTION_BOND_STATE_CHANGED` 广播为准**（下发成功也可能被系统拒绝）。
     */
    fun removeBond(address: String): Boolean
}

/** 反射版 [BondRemover]：`BluetoothDevice.removeBond()`。 */
class ReflectionBondRemover(private val context: Context) : BondRemover {

    override fun removeBond(address: String): Boolean {
        if (missingConnectPermission()) {
            Log.w(TAG, "removeBond: missing BLUETOOTH_CONNECT")
            return false
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter ?: run {
            Log.w(TAG, "removeBond: no bluetooth adapter")
            return false
        }
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: run {
            Log.w(TAG, "removeBond: cannot resolve device $address")
            return false
        }
        return runCatching { HiddenBluetoothApi.removeBond?.invoke(device) as? Boolean }
            .onFailure { Log.w(TAG, "removeBond invoke failed for $address", it) }
            .getOrNull() ?: false
    }

    /** Android 12 之前 `BLUETOOTH_CONNECT` 不是运行时权限，无需检查。 */
    private fun missingConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val TAG = "ReflectionBondRemover"
    }
}
