package com.pocketkeyboard.app.hid

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * App 的固定配对码（「配对 00 码」）。
 *
 * 被控设备要求**输入 PIN / 配对码**时，输入的就是这个码——App 屏幕上展示的与
 * `setPin` 提交的永远一致。取 `0000` 是蓝牙键盘类外设的惯例固定 PIN（4 位，兼容
 * 所有要求 PIN 输入的主机）。
 *
 * 注意：SSP 数字比较 / 本端展示类变体的真实数字由蓝牙控制器生成，App 无法指定；
 * 那些场景下 App 展示的是**当次配对的真实数字**（见 [PairingResponder]），
 * 这个固定码只在 PIN 输入类变体里生效。
 */
const val APP_PAIRING_PIN: String = "0000"

/**
 * 系统配对请求的自动应答结果。
 *
 * 用于日志与测试断言；[HidDeviceTransport] 据此决定是否抑制系统配对框。
 */
enum class PairingAnswer {

    /** 已把 App 配对码通过 `setPin` 提交给系统。 */
    PIN_ANSWERED,

    /** 已自动确认（`setPairingConfirmation(true)`）。 */
    CONFIRMED,

    /**
     * 需要用户在 App 内输入对端展示的 passkey（[HidPairingVariant.PASSKEY_ENTRY]）：
     * 用户提交后由 [PairingResponder.submitPasskey] 完成应答。
     */
    NEED_USER_INPUT,

    /** 无需应答（[HidPairingVariant.DISPLAY]：本端生成并展示数字，对端输入）。 */
    NO_ANSWER_NEEDED,

    /** 未应答：变体不支持 / 前置条件不满足。 */
    SKIPPED,

    /** 想应答但失败（反射被拒等）：系统配对框会照常弹出，用户仍可手动完成。 */
    FAILED,
}

/**
 * 配对请求自动应答器（配对接管的核心策略）。
 *
 * 被控端（iPhone / iPad / Mac / Windows）发起配对（或本 App `createBond` 发起）时，
 * 系统发出 `BluetoothDevice.ACTION_PAIRING_REQUEST` 广播（由 [SystemBondEventSource]
 * 以高优先级有序接收，已接管即抑制系统配对框），随后按 [HidPairingVariant] 分流：
 *
 * | variant | 动作 |
 * |---|---|
 * | `PIN` | `device.setPin(App 配对码字节)`：被控端输入 App 展示的 `0000` 即完成 |
 * | `PASSKEY_ENTRY` | 不自动应答：对端展示的数字要由用户在 App 输入，[submitPasskey] 提交 |
 * | `PASSKEY_CONFIRMATION` / `CONSENT` | `device.setPairingConfirmation(true)`：数字两边一致，无需用户再点 |
 * | `DISPLAY` | 无需应答：数字由系统生成、App 展示，用户在被控端输入 |
 * | `OOB` / `UNKNOWN` | 不干预，走系统配对框 |
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
     * @param passkey 广播携带的数字（数字比较 / 展示类变体有，Just Works / PIN 输入为 null）
     * @return 应答结果；[PairingAnswer.SKIPPED] / [PairingAnswer.FAILED] 都表示
     *     「没有成功代劳」，调用方不要抑制系统配对框
     */
    fun answer(address: String, variant: HidPairingVariant, passkey: Int?): PairingAnswer

    /**
     * 提交用户在 App 内输入的对端 passkey（[HidPairingVariant.PASSKEY_ENTRY] 的续答）。
     *
     * @param passkey 用户输入的数字串（通常 6 位）
     */
    fun submitPasskey(address: String, passkey: String): PairingAnswer = PairingAnswer.SKIPPED
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
 * @param appPinCode App 固定配对码（默认 [APP_PAIRING_PIN]），PIN 输入类变体用它 `setPin`
 * @param targetProvider 系统设备解析器，默认反射 `BluetoothDevice`
 */
class SystemPairingResponder(
    private val appPinCode: String = APP_PAIRING_PIN,
    private val targetProvider: PairingTargetProvider,
) : PairingResponder {

    override fun answer(address: String, variant: HidPairingVariant, passkey: Int?): PairingAnswer =
        when (variant) {
            // 对端要求「输入 PIN」：交出 App 固定配对码，用户在被控设备上输 0000
            HidPairingVariant.PIN -> answerWithPin(address, appPinCode)
            // 对端展示 passkey、要求本端键盘输入：数字在对端屏幕上，必须由用户转述，
            // 这里不能拿 App 配对码硬答（答错会让配对失败），等 submitPasskey
            HidPairingVariant.PASSKEY_ENTRY -> {
                Log.i(TAG, "passkey entry for $address: waiting for user input in app")
                PairingAnswer.NEED_USER_INPUT
            }
            // 对端只要求「确认」：数字由控制器生成且两边一致，直接确认
            HidPairingVariant.PASSKEY_CONFIRMATION, HidPairingVariant.CONSENT -> confirm(address)
            // 本端展示类：无需应答，App 展示系统生成的数字即可
            HidPairingVariant.DISPLAY -> PairingAnswer.NO_ANSWER_NEEDED
            // 带外 / 未知变体：不干预，系统配对框照常处理
            HidPairingVariant.OOB, HidPairingVariant.UNKNOWN -> PairingAnswer.SKIPPED
        }

    override fun submitPasskey(address: String, passkey: String): PairingAnswer {
        val digits = passkey.trim()
        if (digits.isEmpty()) {
            Log.i(TAG, "empty passkey for $address, still waiting")
            return PairingAnswer.NEED_USER_INPUT
        }
        return answerWithPin(address, digits)
    }

    private fun answerWithPin(address: String, pin: String): PairingAnswer {
        if (pin.isEmpty()) {
            // 理论上不会发生（App 配对码是固定常量）：没有码就别抢系统的配对框
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
            Log.i(TAG, "answered pairing with pin for $address")
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
                .onFailure {
                    // cause 单独打出来：Method.invoke 把目标异常包进 InvocationTargetException，
                    // 不打 cause 就看不到真正原因（SecurityException / MIUI 注入框架拦截等）
                    Log.w(TAG, "setPin invoke failed: cause=${it.cause}", it)
                }
                .getOrNull() ?: false

        override fun setPairingConfirmation(confirmed: Boolean): Boolean =
            runCatching { HiddenBluetoothApi.setPairingConfirmation?.invoke(device, confirmed) as? Boolean }
                .onFailure { Log.w(TAG, "setPairingConfirmation invoke failed: cause=${it.cause}", it) }
                .getOrNull() ?: false
    }

    private companion object {
        const val TAG = "ReflectionPairingTarget"
    }
}
