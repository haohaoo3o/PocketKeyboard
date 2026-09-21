package com.pocketkeyboard.app.hid

/**
 * 已连接 / 已配对 HID 主机登记表 + 重连策略（纯 Kotlin，可单测）。
 *
 * 职责：
 * - 维护「当前已连接的 HID 主机」集合与连接顺序（先连上的排在前面，UI 列表用）
 * - 维护「当前控制目标」activeDevice 及其循环切换（五指手势左右滑切设备用）
 * - 记录每个主机的名称 / bond 状态 / HID 连接状态缓存
 * - 计算重连退避时长
 *
 * 线程约定：非线程安全，调用方（HidDeviceTransport）保证在同一执行器上串行调用。
 */
class HidHostRegistry {

    /** 单个主机的缓存信息。 */
    data class Entry(
        val address: String,
        val name: String?,
        val bondState: HidBondState = HidBondState.UNKNOWN,
        /** `BluetoothProfile.STATE_*`。 */
        val connectionState: Int = STATE_UNKNOWN,
        val platform: DevicePlatformHint = DevicePlatformHint.UNKNOWN,
    ) {
        val isConnected: Boolean get() = connectionState == STATE_CONNECTED
        val isBonded: Boolean get() = bondState == HidBondState.BONDED
    }

    private val entries = LinkedHashMap<String, Entry>()

    /** 当前控制目标地址；null 表示没有目标。 */
    var activeAddress: String? = null
        private set

    /** 全部已知主机（连接顺序 + 后续加入的配对设备）。 */
    fun allEntries(): List<Entry> = entries.values.toList()

    /** 当前已连接的主机，按连接顺序排列。 */
    fun connectedEntries(): List<Entry> = entries.values.filter { it.isConnected }

    fun entry(address: String): Entry? = entries[address]

    fun isConnected(address: String): Boolean = entry(address)?.isConnected == true

    /** 已连接主机地址列表（按连接顺序）。 */
    fun connectedAddresses(): List<String> = connectedEntries().map { it.address }

    /** 取一个可用于连接的地址：优先 activeAddress，否则第一个已连接主机。 */
    fun preferredTarget(): String? = activeAddress?.takeIf { entries.containsKey(it) }
        ?: connectedAddresses().firstOrNull()

    /** 更新 / 新增主机信息（保留未知字段）。 */
    fun update(address: String, transform: (Entry?) -> Entry): Entry {
        val current = entries[address]
        val updated = transform(current)
        entries[address] = updated
        return updated
    }

    /** 记录主机名。 */
    fun updateName(address: String, name: String?) = update(address) {
        (it ?: Entry(address, name)).copy(name = name ?: it?.name)
    }

    /** 记录 bond 状态。 */
    fun updateBondState(address: String, bondState: HidBondState) = update(address) {
        (it ?: Entry(address, null)).copy(bondState = bondState)
    }

    /** 记录 HID 连接状态；返回是否发生了「变为已连接」的跳变。 */
    fun updateConnectionState(address: String, connectionState: Int): Boolean {
        val before = entries[address]?.connectionState ?: STATE_UNKNOWN
        update(address) {
            (it ?: Entry(address, null)).copy(connectionState = connectionState)
        }
        return before != STATE_CONNECTED && connectionState == STATE_CONNECTED
    }

    /** 记录平台线索。 */
    fun updatePlatform(address: String, platform: DevicePlatformHint) = update(address) {
        (it ?: Entry(address, null)).copy(platform = platform)
    }

    /** 主机断开：清空连接状态；若它是当前目标则把目标让给别的已连接主机。 */
    fun onDisconnected(address: String) {
        updateConnectionState(address, STATE_DISCONNECTED)
        if (activeAddress == address) {
            activeAddress = connectedAddresses().firstOrNull()
        }
    }

    /** 主机被系统移除 bond。 */
    fun onBondRemoved(address: String) {
        entries.remove(address)
        if (activeAddress == address) {
            activeAddress = connectedAddresses().firstOrNull() ?: entries.keys.firstOrNull()
        }
    }

    /**
     * 设置当前控制目标。
     *
     * @return true 表示目标已切换（地址确实变化且已知）。
     */
    fun setActive(address: String?): Boolean {
        if (address == null) {
            val changed = activeAddress != null
            activeAddress = null
            return changed
        }
        if (!entries.containsKey(address)) return false
        if (activeAddress == address) return false
        activeAddress = address
        return true
    }

    /**
     * 在已连接主机之间循环切换控制目标（五指左右滑手势用）。
     *
     * @return 切换后的地址；只有 0 个或 1 个已连接主机时返回当前值。
     */
    fun cycleActive(): String? {
        val connected = connectedAddresses()
        if (connected.size <= 1) return activeAddress
        val index = connected.indexOf(activeAddress)
        val next = connected[(index + 1).mod(connected.size)]
        activeAddress = next
        return next
    }

    /** 生成 UI 需要的设备信息。 */
    fun deviceInfo(address: String): HidDeviceInfo? {
        val entry = entries[address] ?: return null
        return HidDeviceInfo(
            address = entry.address,
            name = entry.name ?: entry.address,
            platform = entry.platform,
        )
    }

    /** 全部已知设备信息（UI 已配对列表）。 */
    fun deviceInfos(): List<HidDeviceInfo> = allEntries().map {
        HidDeviceInfo(address = it.address, name = it.name ?: it.address, platform = it.platform)
    }

    companion object {
        const val STATE_UNKNOWN = -1

        /** 与 `BluetoothProfile.STATE_DISCONNECTED` 一致。 */
        const val STATE_DISCONNECTED = 0

        /** 与 `BluetoothProfile.STATE_CONNECTING` 一致。 */
        const val STATE_CONNECTING = 1

        /** 与 `BluetoothProfile.STATE_CONNECTED` 一致。 */
        const val STATE_CONNECTED = 2

        /** 与 `BluetoothProfile.STATE_DISCONNECTING` 一致。 */
        const val STATE_DISCONNECTING = 3
    }
}

/**
 * 重连退避策略（纯函数，便于测试）。
 *
 * HID 外设角色下，对端主机通常会在虚拟线缆断开后主动回连；本策略用于
 * 「我方也主动 connect()」的兜底路径，避免对端回连失败后再无补救。
 */
object ReconnectPolicy {

    /** 第 1 次重连等待。 */
    const val FIRST_DELAY_MS = 1_000L

    /** 退避上限。 */
    const val MAX_DELAY_MS = 15_000L

    /** 最多连续重连次数（超过后放弃，等用户手动重连）。 */
    const val MAX_ATTEMPTS = 12

    /** attempt 从 1 开始计数：1s → 2s → 4s → … → 15s。 */
    fun delayFor(attempt: Int): Long {
        if (attempt <= 0) return 0L
        val delay = FIRST_DELAY_MS shl (attempt - 1).coerceAtMost(6)
        return delay.coerceAtMost(MAX_DELAY_MS)
    }

    /** 是否还应继续重连。 */
    fun shouldRetry(attempt: Int): Boolean = attempt < MAX_ATTEMPTS
}

/** 重连调度器抽象（Android 侧用 Handler 实现，测试用 fake）。 */
interface ReconnectScheduler {

    /**
     * 延迟执行一次重连。
     *
     * @return 取消函数；同一 address 重复 schedule 会取消上一次。
     */
    fun schedule(address: String, delayMillis: Long, action: () -> Unit): () -> Unit

    /** 取消某个地址的待执行重连。 */
    fun cancel(address: String)

    /** 取消全部待执行重连。 */
    fun cancelAll()
}
