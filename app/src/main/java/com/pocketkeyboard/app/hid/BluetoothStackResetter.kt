package com.pocketkeyboard.app.hid

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * 注册楔死恢复器：重启系统蓝牙栈，清掉服务端残留的旧 HID 外设注册。
 *
 * ## 为什么需要它
 * 实测（小米 21091116UC / Android 13）：`am force-stop` 杀掉**已注册**的本 App 后，
 * `HidDeviceService` 的客户端死亡清理会卡死在 `deregistering in progress`，
 * 此后所有 `registerApp` 都被原生层以 `application already registered` 弹回、
 * 既无回调也不报错；`unregisterApp` 清不掉（自愈 v2 无效），开关蓝牙进程内
 * 重新注册也救不了老状态。唯一确认有效的恢复是**重启蓝牙栈**
 * （`svc bluetooth disable/enable` 会重启 `com.android.bluetooth` 进程，
 * 原生状态随之重置），普通用户等价操作就是提示里的「开关一次蓝牙」。
 *
 * ## 线程契约
 * - [reset] 绝不阻塞调用线程（生产实现切到内部线程执行 shell 命令）；
 * - 任何失败（无 root / su 超时 / 命令失败）都回调 `onResult(false)`，
 *   **不抛异常、不挂起**——普通用户设备上必须优雅降级到提示路径；
 * - `onResult` 回到调用方约定线程（生产实现 post 回主线程）。
 */
fun interface BluetoothStackResetter {

    /** 尝试重启蓝牙栈；结果经 `onResult(true=已重启)` 异步返回。 */
    fun reset(onResult: (Boolean) -> Unit)

    companion object {
        /** 无 root 降级实现：立即回调 false（直接走提示路径，不崩不卡）。 */
        val UNAVAILABLE = BluetoothStackResetter { onResult -> onResult(false) }
    }
}

/**
 * 基于 `su` 的蓝牙栈重启实现（root 设备专用；普通设备用 [BluetoothStackResetter.UNAVAILABLE]）。
 *
 * 命令序列：探测 `su -c id` → `svc bluetooth disable` → 短暂等待 → `svc bluetooth enable`。
 * 每一步都带超时（[SU_TIMEOUT_MS]），超时即杀进程并按失败返回。
 * 重启完成后，transport 通过适配器 OFF/ON 广播自然走回「取代理 → registerApp」链路。
 */
class RootBluetoothStackResetter : BluetoothStackResetter {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun reset(onResult: (Boolean) -> Unit) {
        val worker = Thread({
            val ok = try {
                probeRoot() && runCommand(DISABLE_COMMAND) && waitDisabled() &&
                    runCommand(ENABLE_COMMAND)
            } catch (e: Exception) {
                Log.w(TAG, "stack reset failed", e)
                false
            }
            Log.i(TAG, "stack reset result=$ok")
            mainHandler.post { onResult(ok) }
        }, "hid-stack-reset")
        worker.isDaemon = true
        worker.start()
    }

    /** 探测 su：必须拿到 uid=0 才继续（普通设备 / su 未授权时快速失败）。 */
    private fun probeRoot(): Boolean {
        val output = runCatching { execForOutput(SU_PROBE) }.getOrNull() ?: return false
        val rooted = output.contains("uid=0")
        Log.i(TAG, "su probe rooted=$rooted")
        return rooted
    }

    private fun runCommand(command: String): Boolean {
        val process = Runtime.getRuntime().exec(arrayOf(SU_BIN, "-c", command))
        val finished = process.waitFor(CMD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            Log.w(TAG, "command timed out: $command")
            return false
        }
        val code = process.exitValue()
        if (code != 0) Log.w(TAG, "command exit=$code: $command")
        return code == 0
    }

    /** 等适配器真正关掉，避免 disable 刚下发就 enable 把状态机绕晕。 */
    private fun waitDisabled(): Boolean {
        repeat(DISABLE_WAIT_POLL_MS) {
            Thread.sleep(500)
            val output = runCatching { execForOutput(BT_STATE_QUERY) }.getOrNull()
            if (output?.trim() == "0") return true
        }
        // 没等到也不算致命：enable 仍会下发，后续以适配器广播为准
        return true
    }

    private fun execForOutput(command: String): String {
        val process = Runtime.getRuntime().exec(arrayOf(SU_BIN, "-c", command))
        // 必须先限时 waitFor 再读输出：若 su 停在授权弹窗上，先读 stdout 会永久阻塞
        val finished = process.waitFor(CMD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            Log.w(TAG, "command timed out: $command")
            return ""
        }
        return process.inputStream.bufferedReader().use { it.readText() }
    }

    private companion object {
        private const val TAG = "HidStackReset"
        private const val SU_BIN = "su"
        private const val SU_PROBE = "id"
        private const val DISABLE_COMMAND = "svc bluetooth disable"
        private const val ENABLE_COMMAND = "svc bluetooth enable"
        private const val BT_STATE_QUERY = "settings get global bluetooth_on"
        private const val CMD_TIMEOUT_MS = 5_000L

        /** 关闭等待轮数：500ms × 8 = 最多 4s。 */
        private const val DISABLE_WAIT_POLL_MS = 8
    }
}
