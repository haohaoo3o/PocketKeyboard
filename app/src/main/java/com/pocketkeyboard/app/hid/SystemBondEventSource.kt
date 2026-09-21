package com.pocketkeyboard.app.hid

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 系统 bond / 配对广播源（方案 A 的配对桥接层）。
 *
 * ## 为什么需要它
 * 手机作为 HID **外设**时，配对是由对端主机（iPhone / iPad / Mac / Windows）发起的 SSP 流程：
 * 1. 对端在蓝牙菜单里选择「口袋键鼠」；
 * 2. 系统弹出配对对话框（数字比较 / Just Works），由**系统 UI** 呈现，App 无法自定义这一步；
 * 3. 系统发出 `BluetoothDevice.ACTION_PAIRING_REQUEST`，bond 状态按
 *    `BOND_NONE → BOND_BONDING → BOND_BONDED` 演进。
 *
 * Android 平台**无法完全自定义 HID 配对码输入流程**（`setPairingConfirmation` 从 API 35 起
 * 还需要 `BLUETOOTH_PRIVILEGED`，普通应用不可用），因此本层的策略是：
 * - 走系统标准 SSP（数字比较 / Just Works）；
 * - 把系统配对框上的 6 位数字（`EXTRA_PAIRING_KEY`）通过
 *   [HidStatusListener.onPairingRequest] 上报，UI 用它作为配对码显示的兜底；
 * - UI 提示用户「在需要控制的设备上确认」。
 *
 * 监听广播：
 * - `BluetoothDevice.ACTION_PAIRING_REQUEST`
 * - `BluetoothDevice.ACTION_BOND_STATE_CHANGED`
 * - `BluetoothAdapter.ACTION_STATE_CHANGED`（蓝牙关闭时 HID 会被自动注销，需重新注册）
 */
class SystemBondEventSource(private val context: Context) : BondEventSource {

    @Volatile
    private var listener: BondEventListener? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val event = intent ?: return
            val current = listener ?: return
            when (event.action) {
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        event.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        event.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val state = event.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    val address = device?.address ?: return
                    current.onBondEvent(
                        BondEvent.BondStateChanged(address, mapBondState(state)),
                    )
                }

                BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        event.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        event.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val address = device?.address ?: return
                    val variant = event.getIntExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT,
                        VARIANT_UNKNOWN,
                    )
                    // passkey / pin 值：仅在广播带了该 extra 时存在（数字比较、PASSKEY 等变体）。
                    val key = if (event.hasExtra(BluetoothDevice.EXTRA_PAIRING_KEY)) {
                        event.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, 0)
                    } else {
                        null
                    }
                    current.onBondEvent(
                        BondEvent.PairingRequest(address, mapPairingVariant(variant), key),
                    )
                }

                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = event.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    current.onBondEvent(
                        BondEvent.AdapterStateChanged(state == BluetoothAdapter.STATE_ON),
                    )
                }
            }
        }
    }

    override fun start(listener: BondEventListener) {
        this.listener = listener
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        try {
            // Android 13+ 注册非系统导出广播必须显式声明 exported flag；
            // 这三个都是受保护的系统广播，用 RECEIVER_NOT_EXPORTED 最安全。
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "registerReceiver failed", e)
        } catch (e: SecurityException) {
            // 缺少 BLUETOOTH_CONNECT 时 Android 12+ 可能拒绝注册
            Log.w(TAG, "registerReceiver denied", e)
        }
    }

    override fun stop() {
        listener = null
        try {
            context.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "unregisterReceiver: not registered", e)
        }
    }

    private fun mapBondState(state: Int): HidBondState = when (state) {
        BluetoothDevice.BOND_BONDED -> HidBondState.BONDED
        BluetoothDevice.BOND_BONDING -> HidBondState.BONDING
        BluetoothDevice.BOND_NONE -> HidBondState.NONE
        else -> HidBondState.UNKNOWN
    }

    /**
     * 配对变体映射。
     *
     * `PAIRING_VARIANT_PIN`(0) 与 `PAIRING_VARIANT_PASSKEY_CONFIRMATION`(2) 是公开常量；
     * 其余取值（1 PASSKEY / 3 CONSENT / 4 DISPLAY_PASSKEY / 5 DISPLAY_PIN /
     * 6 OOB_CONSENT / 7 PIN_16_DIGITS）在公开 SDK 中被隐藏，这里按 AOSP 源码取值直接映射。
     */
    private fun mapPairingVariant(variant: Int): HidPairingVariant = when (variant) {
        BluetoothDevice.PAIRING_VARIANT_PIN -> HidPairingVariant.PIN
        BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION ->
            HidPairingVariant.PASSKEY_CONFIRMATION
        1 -> HidPairingVariant.PASSKEY_ENTRY
        3 -> HidPairingVariant.CONSENT
        4, 5 -> HidPairingVariant.PASSKEY_ENTRY
        6, 7 -> HidPairingVariant.OOB
        else -> HidPairingVariant.UNKNOWN
    }

    companion object {
        private const val TAG = "HidBondEventSource"
        private const val VARIANT_UNKNOWN = -1
    }
}
