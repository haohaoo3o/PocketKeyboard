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
 * 手机作为 HID **外设**时，配对可以由对端主机（iPhone / iPad / Mac / Windows）发起，
 * 也可以由本 App 发起（扫描 → `createBond`）；两条路径最终都会走到：
 * 1. 系统按 SSP 变体发出 `BluetoothDevice.ACTION_PAIRING_REQUEST`；
 * 2. bond 状态按 `BOND_NONE → BOND_BONDING → BOND_BONDED` 演进。
 *
 * 本类负责**注册并接收**这些广播，把它们转成纯 Kotlin 的 [BondEvent] 交给
 * [HidDeviceTransport]：
 * - `ACTION_PAIRING_REQUEST` → [BondEvent.PairingRequest]。该广播是**有序广播**，
 *   这里以 `IntentFilter.SYSTEM_HIGH_PRIORITY` 抢在系统配对对话框之前接收；transport
 *   返回 true（已自动应答 / App 内已展示配对码）时调用 [BroadcastReceiver.abortBroadcast]
 *   **抑制系统配对框**，让配对完全由 App 接管；返回 false 时不拦截，系统对话框照常
 *   兜底（App 没注册 / 在后台 / 反射被拒时的退路）；
 * - `ACTION_BOND_STATE_CHANGED` → [BondEvent.BondStateChanged]；
 * - `ACTION_STATE_CHANGED` → [BondEvent.AdapterStateChanged]（蓝牙关闭时 HID 会被自动
 *   注销，需要重新注册）。
 *
 * 注册参数说明（Android 13+ 行为）：
 * - **导出性**：必须 `RECEIVER_EXPORTED`——广播来自蓝牙进程（uid=1002），NOT_EXPORTED
 *   的系统豁免只覆盖 system_server，会被 BroadcastQueue 拒投（真机日志实证，见
 *   [register] 注释）；这些均为受保护的系统广播，导出注册是安全的；
 * - **权限**：`ACTION_PAIRING_REQUEST` / `ACTION_BOND_STATE_CHANGED` 的接收需要
 *   `BLUETOOTH_CONNECT`（Android 12+ 运行时权限），缺权限时 `registerReceiver` 会抛
 *   `SecurityException`，这里捕获并记日志，不影响 App 运行（HID 层同样会上报不可用）。
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

                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = event.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    current.onBondEvent(
                        BondEvent.AdapterStateChanged(state == BluetoothAdapter.STATE_ON),
                    )
                }
            }
        }
    }

    /**
     * `ACTION_PAIRING_REQUEST` 专用接收器：高优先级有序接收，已接管即抑制系统配对框。
     *
     * 与 [receiver] 分开注册是因为优先级作用在 IntentFilter 上，bond / 适配器广播
     * 不需要抢优先级；混在一个 filter 里会让它们也挂上系统级优先级。
     */
    private val pairingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val event = intent ?: return
            if (event.action != BluetoothDevice.ACTION_PAIRING_REQUEST) return
            val current = listener ?: return
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
            // passkey / pin 值：仅在广播带了该 extra 时存在（数字比较、展示类变体）。
            val key = if (event.hasExtra(BluetoothDevice.EXTRA_PAIRING_KEY)) {
                event.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, 0)
            } else {
                null
            }
            val consumed = current.onBondEvent(
                BondEvent.PairingRequest(address, mapPairingVariant(variant), key),
            )
            // App 已接管（自动应答成功 / App 内展示配对码）→ 截断有序广播，
            // 系统配对对话框（更低优先级的接收者）不再弹出，配对码唯一来源是 App。
            if (consumed && isOrderedBroadcast) {
                abortBroadcast()
                Log.i(TAG, "pairing request for $address consumed, system dialog suppressed")
            }
        }
    }

    override fun start(listener: BondEventListener) {
        this.listener = listener
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        register(filter, receiver)
        // 配对请求走有序广播：SYSTEM_HIGH_PRIORITY 抢在系统配对对话框之前；
        // 配对期间必须保持「已接管即 abort」的顺序——本接收器先于系统框执行。
        val pairingFilter = IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST).apply {
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        }
        register(pairingFilter, pairingReceiver)
    }

    private fun register(filter: IntentFilter, target: BroadcastReceiver) {
        try {
            // 必须 RECEIVER_EXPORTED：这些广播由**蓝牙进程**（com.android.bluetooth,
            // uid=1002）发出，而 NOT_EXPORTED 的系统豁免只覆盖 system_server（uid=1000），
            // 蓝牙进程的广播会被 BroadcastQueue 直接拒投（实测日志：`Exported Denial:
            // sending ...DISCOVERY_STARTED... from com.android.bluetooth (uid=1002)
            // due to receiver ... not specifying RECEIVER_EXPORTED`）——配对请求 /
            // bond 状态因此**从未到达过 App**，自动应答永远不生效、用户只能走系统配对框。
            // 这些都是受保护的系统广播（第三方无法伪造 action），导出注册安全；
            // 接收本身仍需 BLUETOOTH_CONNECT 权限（registerReceiver 的权限防护）。
            ContextCompat.registerReceiver(
                context,
                target,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
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
        unregister(receiver)
        unregister(pairingReceiver)
    }

    private fun unregister(target: BroadcastReceiver) {
        try {
            context.unregisterReceiver(target)
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
     *
     * 修复记录：4 / 5 是**本端展示数字**的变体（用户在被控设备上输入），此前被错映射成
     * [HidPairingVariant.PASSKEY_ENTRY]，导致应答器拿 App 配对码去 `setPin`，与系统生成的
     * 展示码冲突、配对只能退回系统对话框。
     */
    private fun mapPairingVariant(variant: Int): HidPairingVariant = when (variant) {
        BluetoothDevice.PAIRING_VARIANT_PIN -> HidPairingVariant.PIN
        BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION ->
            HidPairingVariant.PASSKEY_CONFIRMATION
        1 -> HidPairingVariant.PASSKEY_ENTRY
        3 -> HidPairingVariant.CONSENT
        4, 5 -> HidPairingVariant.DISPLAY
        6, 7 -> HidPairingVariant.OOB
        else -> HidPairingVariant.UNKNOWN
    }

    companion object {
        private const val TAG = "HidBondEventSource"
        private const val VARIANT_UNKNOWN = -1
    }
}
