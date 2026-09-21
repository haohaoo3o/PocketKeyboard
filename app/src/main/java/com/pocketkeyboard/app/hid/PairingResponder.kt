package com.pocketkeyboard.app.hid

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * App 当前 6 位配对码的提供者（Bug 2a）。
 *
 * 由 `ui` 层的 `MainViewModelStatusBridge` 实现（直接读 `MainViewModel.pinCode`），
 * 这样 hid 层不必反向依赖 UI，配对码仍然是「App 里展示的那个」唯一来源。
 */
interface PairingPinProvider {

    /** 当前 6 位数字配对码；没有（已被清掉 / 尚未生成）时为 null 或空串。 */
    val pairingPinCode: String?
}

/**
 * 系统配对请求的自动应答结果。
 *
 * 只用于日志与测试断言；UI 不直接消费它（系统对话框是否弹出由系统决定）。
 */
enum class PairingAnswer {

    /** 已把 App 配对码通过 `setPin` 提交给系统。 */
    PIN_ANSWERED,

    /** 已自动确认（`setPairingConfirmation(true)`）。 */
    CONFIRMED,

    /** 未应答：变体不支持 / 没有配对码 / 前置条件不满足。 */
    SKIPPED,

    /** 想应答但失败（反射被拒等）：系统配对框会照常弹出，用户仍可手动完成。 */
    FAILED,
}

/**
 * 配对请求自动应答器（Bug 2a 的核心策略）。
 *
 * 被控端（iPhone / iPad / Mac / Windows）发起配对时，系统发出
 * `BluetoothDevice.ACTION_PAIRING_REQUEST` 广播（由 [SystemBondEventSource] 在
 * hid 包内注册接收，`RECEIVER_NOT_EXPORTED` + `BLUETOOTH_CONNECT` 权限防护），
 * 随后按 [HidPairingVariant] 分流：
 *
 * | variant | 动作 |
 * |---|---|
 * | `PASSKEY_ENTRY` / `PIN` | `device.setPin(App 配对码字节)`：被控端输入 App 展示的 6 位码即可完成 |
 * | `PASSKEY_CONFIRMATION` / `CONSENT` | `device.setPairingConfirmation(true)`：数字一致，无需用户再点 |
 * | `OOB` / `UNKNOWN` | 不干预，走系统对话框 |
 *
 * **前置条件由调用方保证**（见 [HidDeviceTransport.onBondEvent]）：只有 App 处于前台
 * 且 HID 外设已注册（`isAppRegistered`）时才调用 [answer]；条件不满足时调用方直接跳过，
 * 系统的配对对话框会照常出现，用户按原来的流程手动确认即可。
 *
 * 实现不依赖 Android framework 类（除 [Log]），可用纯 JVM 单测驱动。
 */
interface PairingResponder {

    /**
     * 尝试自动应答一次配对请求。
     *
     * @param address 发起配对的对端地址
     * @param variant 系统广播里的配对变体
     * @param passkey 广播携带的数字（数字比较 / passkey 变体有，Just Works 为 null）
     * @return 应答结果；[PairingAnswer.SKIPPED] / [PairingAnswer.FAILED] 都表示
     *     「没有成功代劳」，调用方无需补救——系统对话框就是兜底路径
     */
    fun answer(address: String, variant: HidPairingVariant, passkey: Int?): PairingAnswer
}

/** 不自动应答（测试 / 方案 B 空传输使用）。 */
class NoOpPairingResponder : PairingResponder {
    override fun answer(address: String, variant: HidPairingVariant, passkey: Int?): PairingAnswer =
        PairingAnswer.SKIPPED
}

/**
 * 单个系统设备的配对应答能力（反射后的 `BluetoothDevice`）。
 *
 * 抽象成接口是为了单测可以注入假实现，无需真机 / 无需反射。
 */
interface PairingTarget {

    /** 提交配对码（PIN / passkey 输入类变体）。@return true 表示命令已下发。 */
    fun setPin(pin: ByteArray): Boolean

    /** 确认数字比较 / Just Works。@return true 表示命令已下发。 */
    fun setPairingConfirmation(confirmed: Boolean): Boolean
}

/** 按地址取系统设备的配对应答能力（生产环境走反射）。 */
fun interface PairingTargetProvider {
    fun target(address: String): PairingTarget?
}

/**
 * 基于反射的系统配对应答器。
 *
 * @param pinCodeProvider App 当前配对码来源（通常是 `MainViewModelStatusBridge`）
 * @param targetProvider 系统设备解析器，默认反射 `BluetoothDevice`
 */
class SystemPairingResponder(
    private val pinCodeProvider: PairingPinProvider?,
    private val targetProvider: PairingTargetProvider,
) : PairingResponder {

    override fun answer(address: String, variant: HidPairingVariant, passkey: Int?): PairingAnswer =
        when (variant) {
            // 对端要求「输入」：把 App 展示的配对码交出去
            HidPairingVariant.PIN, HidPairingVariant.PASSKEY_ENTRY -> answerWithPin(address)
            // 对端只要求「确认」：数字由对端生成并两边一致，直接确认
            HidPairingVariant.PASSKEY_CONFIRMATION, HidPairingVariant.CONSENT -> confirm(address)
            // 带外 / 未知变体：不干预，系统对话框照常处理
            HidPairingVariant.OOB, HidPairingVariant.UNKNOWN -> PairingAnswer.SKIPPED
        }

    private fun answerWithPin(address: String): PairingAnswer {
        val pin = pinCodeProvider?.pairingPinCode?.trim().orEmpty()
        if (pin.isEmpty()) {
            // App 侧没有配对码（尚未生成 / 已被清掉）：不抢系统的配对框
            Log.i(TAG, "no app pin for $address, let the system dialog handle it")
            return PairingAnswer.SKIPPED
        }
        val target = targetProvider.target(address)
        if (target == null) {
            Log.w(TAG, "no pairing target for $address, fall back to system dialog")
            return PairingAnswer.FAILED
        }
        val answered = runCatching { target.setPin(pin.toByteArray(Charsets.UTF_8)) }
            .onFailure { Log.w(TAG, "setPin reflection failed for $address", it) }
            .getOrDefault(false)
        return if (answered) {
            Log.i(TAG, "answered pairing with app pin for $address")
            PairingAnswer.PIN_ANSWERED
        } else {
            Log.w(TAG, "setPin refused for $address, fall back to system dialog")
            PairingAnswer.FAILED
        }
    }

    private fun confirm(address: String): PairingAnswer {
        val target = targetProvider.target(address) ?: run {
            Log.w(TAG, "no pairing target for $address, fall back to system dialog")
            return PairingAnswer.FAILED
        }
        val confirmed = runCatching { target.setPairingConfirmation(true) }
            .onFailure { Log.w(TAG, "setPairingConfirmation reflection failed for $address", it) }
            .getOrDefault(false)
        return if (confirmed) {
            Log.i(TAG, "auto confirmed pairing for $address")
            PairingAnswer.CONFIRMED
        } else {
            Log.w(TAG, "setPairingConfirmation refused for $address, fall back to system dialog")
            PairingAnswer.FAILED
        }
    }

    private companion object {
        const val TAG = "SystemPairingResponder"
    }
}

/**
 * 反射版 [PairingTargetProvider]：按地址取系统 `BluetoothDevice`，包成 [PairingTarget]。
 *
 * `setPin` / `setPairingConfirmation` 都是隐藏 API，反射解析见 [HiddenBluetoothApi]；
 * 权限不足（未授予 `BLUETOOTH_CONNECT`）时返回 null，让上层回退系统对话框。
 */
class ReflectionPairingTargetProvider(private val context: Context) : PairingTargetProvider {

    override fun target(address: String): PairingTarget? {
        if (missingConnectPermission()) {
            Log.w(TAG, "missing BLUETOOTH_CONNECT, cannot answer pairing")
            return null
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter ?: return null
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return null
        return ReflectionPairingTarget(device)
    }

    /** Android 12 之前 `BLUETOOTH_CONNECT` 不是运行时权限，无需检查。 */
    private fun missingConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
    }

    private class ReflectionPairingTarget(private val device: BluetoothDevice) : PairingTarget {

        override fun setPin(pin: ByteArray): Boolean =
            runCatching { HiddenBluetoothApi.setPin?.invoke(device, pin) as? Boolean }
                .onFailure { Log.w(TAG, "setPin invoke failed", it) }
                .getOrNull() ?: false

        override fun setPairingConfirmation(confirmed: Boolean): Boolean =
            runCatching { HiddenBluetoothApi.setPairingConfirmation?.invoke(device, confirmed) as? Boolean }
                .onFailure { Log.w(TAG, "setPairingConfirmation invoke failed", it) }
                .getOrNull() ?: false
    }

    private companion object {
        const val TAG = "ReflectionPairingTarget"
    }
}
