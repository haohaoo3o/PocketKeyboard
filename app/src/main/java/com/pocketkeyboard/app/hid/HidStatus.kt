package com.pocketkeyboard.app.hid

/**
 * HID 后端对外暴露的状态类型与回调（纯 Kotlin，不引用任何 Android / Compose 类）。
 *
 * 设计约定：本层**不直接操作 UI**，所有状态通过 [HidStatusListener] 上报，
 * 由 `MainViewModelStatusBridge` 转发到 `MainViewModel` 的 StateFlow。
 */

/** 连接不可用原因（方案 B / 运行期降级时上报，UI 用 strings.xml 文案展示）。 */
enum class HidUnavailableReason {
    /** 本机没有蓝牙硬件。 */
    BLUETOOTH_UNSUPPORTED,

    /** 蓝牙适配器存在但未开启。 */
    BLUETOOTH_OFF,

    /**
     * 系统版本低于 Android 9（API 28）：`BluetoothHidDevice` profile 从 API 28 才对外开放。
     */
    ANDROID_TOO_OLD,

    /**
     * `getProfileProxy(HID_DEVICE)` 未回调 / 回调了非 HidDevice 代理：常见于部分定制 ROM
     * 直接禁用 HidDevice profile（此时走方案 B 空实现）。
     */
    PROFILE_UNAVAILABLE,

    /** registerApp 被拒：同一时刻只能有一个 App 注册 HID 外设（如其他输入法助手已占用）。 */
    REGISTRATION_REJECTED,

    /** 缺少 BLUETOOTH_CONNECT 等运行时权限。 */
    MISSING_PERMISSION,
}

/** 系统 bond（配对）状态，桥接到 `BluetoothDevice.BOND_*`。 */
enum class HidBondState {
    NONE,
    BONDING,
    BONDED,

    /** 未知（查询失败或权限不足）。 */
    UNKNOWN,
}

/**
 * 系统配对请求变体，对应 `BluetoothDevice.PAIRING_VARIANT_*`。
 *
 * Android 手机作为 HID **外设**时，配对通常由对端主机发起，因此常见的是
 * [PASSKEY_CONFIRMATION]（数字比较，两边显示同一 6 位数字）与 [DISPLAY]
 * （本端生成数字，用户在被控设备上输入）。
 *
 * 与 AOSP `BluetoothDevice` 常量的对应：PIN=0、PASSKEY(=PASSKEY_ENTRY)=1、
 * PASSKEY_CONFIRMATION=2、CONSENT=3、DISPLAY_PASSKEY=4、DISPLAY_PIN=5、
 * OOB_CONSENT=6、PIN_16_DIGITS=7。
 */
enum class HidPairingVariant {
    /** 对端要求输入 PIN：应答 `setPin(App 配对码)`，用户在被控设备上输入同一个码。 */
    PIN,

    /** 数字比较：两边显示同一 6 位数字，应答 `setPairingConfirmation(true)`。 */
    PASSKEY_CONFIRMATION,

    /** Just Works：无数字确认，应答 `setPairingConfirmation(true)`。 */
    CONSENT,

    /** 对端展示 passkey，要求在本端键盘输入：应答 `setPin(用户输入的数字)`。 */
    PASSKEY_ENTRY,

    /**
     * 本端展示 passkey / PIN（DISPLAY_PASSKEY=4 / DISPLAY_PIN=5），用户在被控设备上输入。
     *
     * **不需要任何应答**（数字由系统控制器生成，随广播 EXTRA_PAIRING_KEY 给出），
     * App 只负责把它显示出来；误调 `setPin` 会破坏配对流程。
     */
    DISPLAY,

    /** OOB / PIN_16_DIGITS 等带外或罕见变体：不干预，走系统配对框。 */
    OOB,

    /** 未知变体。 */
    UNKNOWN,
}

/**
 * 本层维护的设备信息（平台判定结果）。
 *
 * 与 UI 层的 `com.pocketkeyboard.app.ui.PairedDevice` 解耦：本层只给「线索」，
 * 由 bridge 转成 UI 契约类型，便于 JVM 单元测试。
 */
data class HidDeviceInfo(
    val address: String,
    val name: String,
    val platform: DevicePlatformHint,
)

/** 平台线索：[DevicePlatformDetector] 的判定结果。 */
enum class DevicePlatformHint {
    /** 命中 Apple OUI / 名称特征。 */
    APPLE,

    /** 明确不是苹果设备。 */
    OTHER,

    /** 名称缺失且 OUI 未命中，无法判定。 */
    UNKNOWN,
}

/** HID 后端状态回调（由 bridge 转成 MainViewModel 状态）。 */
interface HidStatusListener {

    /** HID 外设 App 注册结果变化（registerApp / unregisterApp / 蓝牙关闭导致自动注销）。 */
    fun onAppRegistrationChanged(registered: Boolean) {}

    /**
     * 已配对（bonded）设备列表变化：来自 bond 广播 + `BluetoothAdapter.getBondedDevices()`。
     *
     * 注意这是「系统已配对」列表，不等于「当前已连接」；已连接集合见 [onHostConnected]。
     */
    fun onPairedDevicesChanged(devices: List<HidDeviceInfo>) {}

    /** 某个 HID 主机（对端）已建立 HID 连接。 */
    fun onHostConnected(device: HidDeviceInfo) {}

    /** 某个 HID 主机断开。 */
    fun onHostDisconnected(address: String) {}

    /**
     * 正在**主动**连接某个主机（设备侧发起 HID L2CAP，Bug A2）。
     *
     * 与 [onReconnecting] 的区别：本回调覆盖一切「我方发起连接」的路径
     * （点设备行 / 注册后自动连接 / 退避重试），UI 据此把设备行切成「连接中…」。
     */
    fun onHostConnecting(address: String) {}

    /**
     * 主动连接下发失败（`BluetoothHidDevice.connect` 返回 false，Bug A2）。
     *
     * 只表示「命令没下发成功」；底层仍会按 [ReconnectPolicy] 退避重试
     * （每次重试都会再走一次 [onHostConnecting]）。UI 可显示「连接失败」。
     */
    fun onHostConnectFailed(address: String) {}

    /**
     * 当前已连接 HID 对端地址集合变化（Bug 2b：配对页据此显示「已连接」标识）。
     *
     * 与 [onHostConnected] / [onHostDisconnected] 的区别：这两个回调是「事件」，
     * 漏一个就会与真实状态漂移；本回调是 registry 当前状态的**全量快照**，
     * 任何导致连接集合变化的路径（连接 / 断开 / 蓝牙关闭 / unregisterApp）都会推送。
     */
    fun onConnectedDevicesChanged(addresses: Set<String>) {}

    /** 当前控制目标变化；null 表示无可用目标。 */
    fun onActiveDeviceChanged(device: HidDeviceInfo?) {}

    /** bond 状态变化。 */
    fun onBondStateChanged(address: String, state: HidBondState) {}

    /**
     * 系统弹出配对请求。
     *
     * @param pinOrPasskey 系统配对框上的数字（PIN / passkey / 数字比较值），取不到为 null。
     *                     UI 用它作为 `MainViewModel.pinCode` 的兜底显示。
     */
    fun onPairingRequest(address: String, variant: HidPairingVariant, pinOrPasskey: Int?) {}

    /**
     * 配对自动应答结果（[onPairingRequest] 之后紧跟一次）。
     *
     * UI 据此切换提示：应答成功 → 「已自动确认」；被平台封锁（`setPairingConfirmation` /
     * `setPin` 需要 `BLUETOOTH_PRIVILEGED`，普通应用调用抛 SecurityException）→
     * 提示引导用户在系统配对框完成确认，**绝不谎称已自动确认**。
     */
    fun onPairingAutoAnswered(address: String, answer: PairingAnswer) {}

    /** 连接不可用（方案 B 或运行期降级），UI 展示对应中文提示。 */
    fun onUnavailable(reason: HidUnavailableReason) {}

    /** 需要重新连接某个已配对主机（UI 可显示「正在重新连接…」）。 */
    fun onReconnecting(address: String, attempt: Int) {}

    /**
     * 主机回写键盘 LED 状态（Num Lock / Caps Lock / Scroll Lock）。
     *
     * 来自 `BluetoothHidDevice.Callback.onSetReport` 的 Output 报告，
     * 键盘页可据此显示 Caps Lock 是否生效。
     */
    fun onLedStateChanged(numLock: Boolean, capsLock: Boolean, scrollLock: Boolean) {}
}

/** 空实现，便于 bridge / 测试按需只覆盖关心的方法。 */
open class NoOpHidStatusListener : HidStatusListener
