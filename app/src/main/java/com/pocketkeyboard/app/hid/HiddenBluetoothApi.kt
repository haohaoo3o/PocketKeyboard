package com.pocketkeyboard.app.hid

import android.bluetooth.BluetoothDevice
import android.util.Log
import java.lang.reflect.Method

/**
 * 蓝牙隐藏 API 的反射入口（配对码应答 / 解除配对都用它）。
 *
 * ## 为什么必须反射
 * `BluetoothDevice.setPin(byte[])`、`setPairingConfirmation(boolean)`、`removeBond()`
 * 在公开 SDK 里**不存在**（`@hide`），只能按方法名反射调用。
 *
 * ## 反射风险（务必了解，改代码前先读）
 * 1. **非 SDK 接口限制**：Android 9+ 对非公开 API 有 greylist / blocklist 分级。
 *    这三个方法在不同版本上分级不同，`getMethod` 可能抛 `NoSuchMethodException`
 *    （被 blocklist 的接口连 `getMethod` 都拿不到），这里统一降级为 null。
 * 2. **权限收紧**：Android 15（API 35）起 `setPin` / `setPairingConfirmation` 需要
 *    `BLUETOOTH_PRIVILEGED`（signature|privileged 级），普通应用调用会抛
 *    `SecurityException`；`removeBond` 在部分版本同样受限。
 * 3. **调用异常**：`Method.invoke` 可能抛 `InvocationTargetException` /
 *    `SecurityException` / `IncompatibleClassChangeError` 等各种运行时异常。
 *
 * 因此所有调用方都必须 `runCatching` 包裹、失败即回退系统对话框 / 视为未删除，
 * **绝不向上抛异常**；本对象的 lazy 解析同样带 `runCatching`，拿不到方法就是 null。
 */
internal object HiddenBluetoothApi {

    /** `BluetoothDevice.setPin(byte[])`：提交配对码（PIN / passkey 输入类变体）。 */
    val setPin: Method? by lazy { resolve("setPin", ByteArray::class.java) }

    /** `BluetoothDevice.setPairingConfirmation(boolean)`：确认数字比较 / Just Works。 */
    val setPairingConfirmation: Method? by lazy {
        resolve("setPairingConfirmation", java.lang.Boolean.TYPE)
    }

    /** `BluetoothDevice.removeBond()`：解除系统配对（侧滑删除用）。 */
    val removeBond: Method? by lazy { resolve("removeBond") }

    private fun resolve(name: String, vararg parameterTypes: Class<*>): Method? =
        runCatching { BluetoothDevice::class.java.getMethod(name, *parameterTypes) }
            .onFailure { Log.w(TAG, "hidden API BluetoothDevice.$name unavailable", it) }
            .getOrNull()

    private const val TAG = "HiddenBluetoothApi"
}
