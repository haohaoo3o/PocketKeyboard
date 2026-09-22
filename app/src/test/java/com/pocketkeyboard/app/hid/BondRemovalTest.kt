package com.pocketkeyboard.app.hid

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Bug 2a：解除配对的成败判据（bond 广播）单测。
 *
 * 覆盖两条路径：
 * 1. **判据**：[removeBondAndConfirm] 必须只看 [BondRemovalConfirmer] 的结论，
 *    反射返回值（MIUI 上常为 false 却实际删除成功）只作日志参考；
 * 2. **广播**：[BroadcastBondRemovalConfirmer] 真的在等 `ACTION_BOND_STATE_CHANGED`
 *    → `BOND_NONE`，并在超时后用设备状态查询兜底。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BondRemovalTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    // ------------------------------------------------------------ 判据

    @Test
    fun `reflection false but confirmed removal counts as removed`() {
        // MIUI / Android 13 的典型现象：反射返回 false，系统其实已经解绑
        val remover = object : BondRemover {
            override fun removeBond(address: String) = false
        }
        val confirmer = object : BondRemovalConfirmer {
            override fun startWatching(address: String) = Unit
            override suspend fun awaitRemoved(address: String, timeoutMillis: Long) = true
            override fun stop() = Unit
        }
        val outcome = runBlocking {
            removeBondAndConfirm(context, ADDRESS, remover, confirmer, timeoutMillis = 100)
        }
        assertEquals(BondRemoval.Removed, outcome)
    }

    @Test
    fun `reflection true but bond still present counts as failed`() {
        val remover = object : BondRemover {
            override fun removeBond(address: String) = true
        }
        val confirmer = object : BondRemovalConfirmer {
            override fun startWatching(address: String) = Unit
            override suspend fun awaitRemoved(address: String, timeoutMillis: Long) = false
            override fun stop() = Unit
        }
        val outcome = runBlocking {
            removeBondAndConfirm(context, ADDRESS, remover, confirmer, timeoutMillis = 100)
        }
        assertTrue(outcome is BondRemoval.Failed)
    }

    @Test
    fun `throwing remover does not crash and still defers to the confirmer`() {
        // 反射被 block / 抛异常时命令没下发，但成败仍以系统实际 bond 状态为准：
        // 广播确认已解除 → Removed（只是 dispatched=false 记在日志里）
        val remover = object : BondRemover {
            override fun removeBond(address: String): Nothing = error("reflection blocked")
        }
        val confirmer = object : BondRemovalConfirmer {
            override fun startWatching(address: String) = Unit
            override suspend fun awaitRemoved(address: String, timeoutMillis: Long) = true
            override fun stop() = Unit
        }
        val outcome = runBlocking {
            removeBondAndConfirm(context, ADDRESS, remover, confirmer, timeoutMillis = 100)
        }
        assertEquals(BondRemoval.Removed, outcome)
    }

    @Test
    fun `no dispatch and no confirmation reports failed`() {
        // 命令没下发（反射被拒）+ 也没有任何解除证据 → 失败，UI 提示用户重试
        val remover = object : BondRemover {
            override fun removeBond(address: String): Nothing = error("reflection blocked")
        }
        val confirmer = object : BondRemovalConfirmer {
            override fun startWatching(address: String) = Unit
            override suspend fun awaitRemoved(address: String, timeoutMillis: Long) = false
            override fun stop() = Unit
        }
        val outcome = runBlocking {
            removeBondAndConfirm(context, ADDRESS, remover, confirmer, timeoutMillis = 100)
        }
        assertTrue(outcome is BondRemoval.Failed)
    }

    @Test
    fun `throwing confirmer does not crash and reports failed`() {
        val remover = object : BondRemover {
            override fun removeBond(address: String) = true
        }
        val confirmer = object : BondRemovalConfirmer {
            override fun startWatching(address: String) = Unit
            override suspend fun awaitRemoved(address: String, timeoutMillis: Long): Nothing =
                error("receiver blew up")
            override fun stop() = Unit
        }
        val outcome = runBlocking {
            removeBondAndConfirm(context, ADDRESS, remover, confirmer, timeoutMillis = 100)
        }
        assertTrue(outcome is BondRemoval.Failed)
    }

    // ------------------------------------------------------------ 广播

    @Test
    fun `bond none broadcast confirms removal`() {
        val confirmer = BroadcastBondRemovalConfirmer(context)
        val outcome = awaitOnWorkerThread(confirmer, ADDRESS, timeoutMillis = 5_000) {
            context.sendBroadcast(bondIntent(ADDRESS, BluetoothDevice.BOND_NONE))
        }
        assertEquals(BondRemoval.Removed, outcome)
    }

    @Test
    fun `bond bonded broadcast keeps it failed`() {
        // 让 shadow 适配器认为设备仍处于 bonded（否则轮询通道会先得出「已解除」）
        shadowOf(BluetoothAdapter.getDefaultAdapter())
            .setBondedDevices(setOf(remoteDevice(ADDRESS)))
        val confirmer = BroadcastBondRemovalConfirmer(context)
        val outcome = awaitOnWorkerThread(confirmer, ADDRESS, timeoutMillis = 2_000) {
            context.sendBroadcast(bondIntent(ADDRESS, BluetoothDevice.BOND_BONDED))
        }
        assertTrue(outcome is BondRemoval.Failed)
    }

    @Test
    fun `other device bond broadcasts are ignored`() {
        val confirmer = BroadcastBondRemovalConfirmer(context)
        val outcome = awaitOnWorkerThread(confirmer, ADDRESS, timeoutMillis = 1_000) {
            // 先发别的设备的广播（应被忽略），再发目标设备的 BOND_NONE
            context.sendBroadcast(bondIntent(OTHER_ADDRESS, BluetoothDevice.BOND_NONE))
            context.sendBroadcast(bondIntent(ADDRESS, BluetoothDevice.BOND_NONE))
        }
        assertEquals(BondRemoval.Removed, outcome)
    }

    @Test
    fun `timeout falls back to a device state query`() {
        // 广播丢了：Robolectric 的 shadow 蓝牙适配器里没有任何已配对设备，
        // 兜底查询（不在 bondedDevices 里即已解除）应得出「已解除」
        val confirmer = BroadcastBondRemovalConfirmer(context)
        confirmer.startWatching(ADDRESS)
        val confirmed = runBlocking { confirmer.awaitRemoved(ADDRESS, timeoutMillis = 1_200) }
        confirmer.stop()
        assertTrue("轮询兜底应判定为已解除", confirmed)
    }

    @Test
    fun `confirmation fails when the device is still bonded after timeout`() {
        // 给 shadow 适配器塞一个已配对设备 → 兜底查询认定「没删掉」
        val adapter = BluetoothAdapter.getDefaultAdapter()
        shadowOf(adapter).setBondedDevices(setOf(remoteDevice(ADDRESS)))
        val confirmer = BroadcastBondRemovalConfirmer(context)
        confirmer.startWatching(ADDRESS)
        val confirmed = runBlocking { confirmer.awaitRemoved(ADDRESS, timeoutMillis = 1_200) }
        confirmer.stop()
        assertFalse("设备仍在 bonded 列表里应判定失败", confirmed)
    }

    // ------------------------------------------------------------ 工具

    /**
     * 在工作线程里跑两段式确认（startWatching → awaitRemoved），主线程负责发广播并 idle
     * 主 looper 投递：Robolectric 的 PAUSED looper 模式下广播投递必须显式 idle。
     */
    private fun awaitOnWorkerThread(
        confirmer: BondRemovalConfirmer,
        address: String,
        timeoutMillis: Long,
        broadcast: () -> Unit,
    ): BondRemoval {
        val done = CountDownLatch(1)
        var confirmed = false
        Thread {
            runBlocking {
                confirmer.startWatching(address)
                confirmed = runBlocking { confirmer.awaitRemoved(address, timeoutMillis) }
            }
            done.countDown()
        }.start()
        // 等工作线程把接收器注册好（registerReceiver 是同步调用，给一点余量）
        Thread.sleep(300)
        broadcast()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("等待确认超时（广播没送达？）", done.await(15, TimeUnit.SECONDS))
        confirmer.stop()
        return if (confirmed) {
            BondRemoval.Removed
        } else {
            BondRemoval.Failed("bond still present")
        }
    }

    private fun remoteDevice(address: String): BluetoothDevice =
        BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)

    private fun bondIntent(address: String, bondState: Int): Intent =
        Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED).apply {
            putExtra(BluetoothDevice.EXTRA_DEVICE, remoteDevice(address))
            putExtra(BluetoothDevice.EXTRA_BOND_STATE, bondState)
        }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:01"
        const val OTHER_ADDRESS = "AA:BB:CC:DD:EE:02"
    }
}
