package com.pocketkeyboard.app.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

/**
 * 反射版 [BondRemover]：`BluetoothDevice.removeBond()`。
 */
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

/**
 * 「解除配对是否真的成功」的结论（Bug 2a 修复）。
 *
 * 为什么需要单独的结果类型：`BluetoothDevice.removeBond()` 的返回值在
 * MIUI / Android 13 上经常是 false 却**实际删除成功**（实测反馈：侧滑删除总是提示
 * 「删除失败」，但系统设置里已经清掉）。因此反射返回值只作参考，判据换成系统
 * `ACTION_BOND_STATE_CHANGED` → `BOND_NONE` 广播（见 [BroadcastBondRemovalConfirmer]）。
 */
sealed interface BondRemoval {

    /** bond 已解除（收到 BOND_NONE 广播，或广播丢失但设备状态查询确认已解除）。 */
    data object Removed : BondRemoval

    /**
     * 解除失败：反射未下发成功 / 等待确认超时，且设备仍处于 bonded。
     *
     * @param detail 供日志与 UI 兜底文案使用的简要原因。
     */
    data class Failed(val detail: String) : BondRemoval
}

/**
 * bond 解除确认器：监听 `ACTION_BOND_STATE_CHANGED`，等目标地址变为 [BOND_NONE][BluetoothDevice.BOND_NONE]。
 *
 * 抽象成接口是为了单测可以注入假实现，无需 Robolectric 蓝牙桩。
 *
 * **两段式（startWatching → awaitRemoved → stop）是刻意的**：实测（小米 21091116UC +
 * Android 13）`removeBond()` 下发后系统在毫秒级完成解绑并发出广播，如果先 removeBond
 * 再注册接收器，广播早就发完了、接收器什么也收不到（第一版就是这么写的，只能等满 5 秒
 * 超时再靠轮询兜底）。
 */
interface BondRemovalConfirmer {

    /** 注册监听。**必须在 [BondRemover.removeBond] 之前调用**，否则可能错过瞬时完成的解绑广播。 */
    fun startWatching(address: String)

    /**
     * 等待 [address] 的 bond 状态变为 `BOND_NONE`。
     *
     * @param timeoutMillis 等待上限（毫秒）。
     * @return true 表示已确认解除；false 表示超时前设备仍 bonded（或确认失败）。
     */
    suspend fun awaitRemoved(address: String, timeoutMillis: Long): Boolean

    /** 注销监听（幂等：未注册 / 重复调用都安全）。 */
    fun stop()
}

/** 等待 bond 广播的超时（毫秒）：删除要等系统走完解绑流程，不能比这更短。 */
const val BOND_REMOVAL_TIMEOUT_MS = 5_000L

/**
 * 基于系统广播 + 轮询兜底的 [BondRemovalConfirmer]。
 *
 * 实现要点：
 * - 只关心目标地址的广播，`BOND_BONDING` / `BOND_BONDED` 视为「还没删掉」，
 *   `BOND_NONE` 立即确认成功；
 * - 广播与轮询（每 [POLL_INTERVAL_MS] 查一次 `bondedDevices`）**双通道并行**：
 *   广播通常 <100ms 到达；若 ROM 不给动态 receiver 投递（部分 MIUI 版本），
 *   轮询通道仍能在几百毫秒内给出结论，不会把用户卡在等待里；
 * - 用 [androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED] 注册
 *   （受保护的系统广播），注册失败（缺 `BLUETOOTH_CONNECT` 时 Android 12+ 会抛
 *   `SecurityException`）时降级为纯轮询，不影响 UI；
 * - [stop] 幂等，无论成功失败都由调用方在 finally 里收尾。
 */
class BroadcastBondRemovalConfirmer(private val context: Context) : BondRemovalConfirmer {

    private var receiver: BroadcastReceiver? = null
    private var bondState: CompletableDeferred<Int>? = null

    override fun startWatching(address: String) {
        if (receiver != null) return
        val deferred = CompletableDeferred<Int>()
        val listening = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val device = intent.deviceOrNull() ?: return
                if (!device.address.equals(address, ignoreCase = true)) return
                val state = intent.getIntExtra(
                    BluetoothDevice.EXTRA_BOND_STATE,
                    BluetoothDevice.BOND_NONE,
                )
                if (state == BluetoothDevice.BOND_BONDING) return
                deferred.complete(state)
            }
        }
        val registered = runCatching {
            ContextCompat.registerReceiver(
                context,
                listening,
                IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure { Log.w(TAG, "register bond receiver failed for $address", it) }.isSuccess
        if (registered) {
            receiver = listening
            bondState = deferred
        }
    }

    override suspend fun awaitRemoved(address: String, timeoutMillis: Long): Boolean {
        val deferred = bondState
        if (deferred == null) {
            // 接收器没注册上（缺权限等）：只能靠轮询兜底
            Log.w(TAG, "no bond receiver registered for $address, polling only")
            return pollUntilRemoved(address, timeoutMillis)
        }
        val pollJob = CoroutineScope(Dispatchers.Default).launch {
            var remaining = timeoutMillis
            while (remaining > 0) {
                delay(POLL_INTERVAL_MS)
                remaining -= POLL_INTERVAL_MS
                if (queryBondRemoved(address)) {
                    Log.i(TAG, "poll confirmed bond removed for $address")
                    deferred.complete(BluetoothDevice.BOND_NONE)
                    return@launch
                }
            }
        }
        return try {
            val observed = withTimeoutOrNull(timeoutMillis) { deferred.await() }
            observed == BluetoothDevice.BOND_NONE
        } finally {
            pollJob.cancel()
        }
    }

    override fun stop() {
        val listening = receiver ?: return
        receiver = null
        bondState = null
        runCatching { context.unregisterReceiver(listening) }
            .onFailure { Log.w(TAG, "unregister bond receiver failed", it) }
    }

    /** 纯轮询兜底（没有接收器时用）。 */
    private suspend fun pollUntilRemoved(address: String, timeoutMillis: Long): Boolean {
        var remaining = timeoutMillis
        while (remaining > 0) {
            delay(POLL_INTERVAL_MS)
            remaining -= POLL_INTERVAL_MS
            if (queryBondRemoved(address)) return true
        }
        return false
    }

    /**
     * 直接查询系统：设备不在 bondedDevices 里即视为已解除。
     *
     * `bondedDevices` 需要 `BLUETOOTH_CONNECT`（Android 12+ 运行时权限）：未授予时抛
     * SecurityException，被下面的 runCatching 兜住并按「无法确认」处理（返回 false），
     * 绝不向上抛。lint 的流分析看不穿 runCatching，这里显式声明。
     */
    @SuppressLint("MissingPermission")
    private fun queryBondRemoved(address: String): Boolean {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter ?: return false
        return runCatching {
            val stillBonded = adapter.bondedDevices.any {
                it.address.equals(address, ignoreCase = true)
            }
            !stillBonded
        }.onFailure { Log.w(TAG, "bond state query failed for $address", it) }.getOrDefault(false)
    }

    private fun Intent.deviceOrNull(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private companion object {
        const val TAG = "BroadcastBondRemovalConfirmer"

        /** 轮询间隔（毫秒）。 */
        const val POLL_INTERVAL_MS = 250L
    }
}

/**
 * 解除 bond 并等到系统确认（Bug 2a 的完整流程）。
 *
 * 顺序（**必须先监听再下令**，否则可能错过瞬时完成的解绑广播）：
 * 1. [BondRemovalConfirmer.startWatching] 注册 bond 广播监听；
 * 2. 下发 `removeBond()`——返回值仅记录日志，**不作为成功依据**；
 * 3. [BondRemovalConfirmer.awaitRemoved] 等广播 / 轮询确认；
 * 4. finally [BondRemovalConfirmer.stop] 收尾。
 *
 * @param timeoutMillis 等确认的上限。
 */
suspend fun removeBondAndConfirm(
    context: Context,
    address: String,
    remover: BondRemover = ReflectionBondRemover(context),
    confirmer: BondRemovalConfirmer = BroadcastBondRemovalConfirmer(context),
    timeoutMillis: Long = BOND_REMOVAL_TIMEOUT_MS,
): BondRemoval {
    confirmer.startWatching(address)
    try {
        val dispatched = runCatching { remover.removeBond(address) }
            .onFailure { Log.w(TAG, "removeBond threw for $address", it) }
            .getOrDefault(false)
        Log.i(TAG, "removeBond dispatched=$dispatched for $address, awaiting confirmation")
        val removed = runCatching { confirmer.awaitRemoved(address, timeoutMillis) }
            .onFailure { Log.w(TAG, "bond confirmation failed for $address", it) }
            .getOrDefault(false)
        return if (removed) {
            BondRemoval.Removed
        } else {
            BondRemoval.Failed("removeBond dispatched=$dispatched, bond still present")
        }
    } finally {
        confirmer.stop()
    }
}

private const val TAG = "BondRemoval"
