package com.pocketkeyboard.app.hid

import com.pocketkeyboard.app.R

/**
 * HID 状态文案映射：把 [HidUnavailableReason] / [HidPairingVariant] 转成 strings.xml 资源 ID。
 *
 * 约定：**所有用户可见文案都必须来自 strings.xml**（见跨模块契约第 4 条），
 * 本文件是 hid 包内唯一引用 `R.string.*` 的地方，便于集中维护中文文案。
 */
object HidStatusText {

    /** 不可用原因 → 提示文案。 */
    fun unavailableMessage(reason: HidUnavailableReason): Int = when (reason) {
        HidUnavailableReason.BLUETOOTH_UNSUPPORTED -> R.string.hid_unavailable_bluetooth_unsupported
        HidUnavailableReason.BLUETOOTH_OFF -> R.string.hid_unavailable_bluetooth_off
        HidUnavailableReason.ANDROID_TOO_OLD -> R.string.hid_unavailable_android_too_old
        HidUnavailableReason.PROFILE_UNAVAILABLE -> R.string.hid_unavailable_profile
        HidUnavailableReason.REGISTRATION_REJECTED -> R.string.hid_unavailable_registration
        HidUnavailableReason.MISSING_PERMISSION -> R.string.hid_unavailable_permission
    }

    /** 配对请求变体 → 提示文案（配对码本身由 `MainViewModel.pinCode` 展示）。 */
    fun pairingHint(variant: HidPairingVariant): Int = when (variant) {
        HidPairingVariant.PIN,
        HidPairingVariant.PASSKEY_ENTRY,
        HidPairingVariant.OOB,
        -> R.string.hid_pairing_step_3
        HidPairingVariant.PASSKEY_CONFIRMATION,
        HidPairingVariant.CONSENT,
        HidPairingVariant.UNKNOWN,
        -> R.string.hid_pairing_confirm_hint
    }
}
