package com.pocketkeyboard.app.hid

import android.app.ActivityManager
import android.content.Context
import android.util.Log

/**
 * App 是否处于前台（用户可见）的判定，供配对自动应答的前置条件使用（Bug 2a）。
 *
 * 只在 App 前台时才代劳配对：用户不在 App 里时，系统的配对对话框是唯一合理的交互，
 * App 抢答会让用户在没有上下文的情况下完成配对。
 *
 * 实现用 `ActivityManager.getMyMemoryState`（静态方法、无需任何权限，且
 * Android 12+ 对其它应用已收紧的前提下本进程信息仍然可用）：
 * - importance ≤ [ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND]：前台可见；
 * - 系统配对对话框盖在 App 之上时，本进程会退到 `IMPORTANCE_VISIBLE`，
 *   这仍然算「用户在 App 里」，因此阈值放到 VISIBLE；
 * - 退到后台 / 仅存活着缓存进程时 importance ≥ SERVICE，判为不在前台。
 *
 * 判定异常（ROM 行为差异）时保守返回 false——代价只是回退系统对话框，不会崩溃。
 */
internal class SystemAppVisibility(private val context: Context) {

    fun isForeground(): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        return runCatching {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        }.onFailure { Log.w(TAG, "importance check failed", it) }
            .getOrDefault(false)
    }

    private companion object {
        const val TAG = "SystemAppVisibility"
    }
}
