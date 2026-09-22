package com.pocketkeyboard.app.gesture

import android.util.Log

/**
 * 五指挥势引擎的 logcat 观测点（tag 固定 [TAG]）。
 *
 * ## 为什么引擎自己打日志
 *
 * 真机排查「五指挥势不触发」时，唯一能看到内部状态的办法就是日志：事件有没有到
 * 手势层、凑指窗口续期到几点、为什么放手、几何差多少、判成了哪一支——全都在
 * `pointerInput` 协程里，UI 上看不见。上一轮只能靠猜，于是把每个判定分支都打出来：
 *
 * ```
 * adb logcat -s PocketGesture
 * ```
 *
 * - [decision]：一次手势的**唯一**结论行（凑满 / 放手原因 / 判定结果 / 触发回调），
 *   一条手势一行，是排查的主入口；
 * - [trace]：凑指过程中的细节（每根新手指、续期后的截止时间、几何增量），量不大，
 *   只在需要看过程时有用；
 * - [warn]：本不该发生的防御分支（例如凑满 5 指却算不出几何）。
 *
 * 日志只在「一次手势」的粒度上产生（每根新手指一行 + 结论一行），正常打字 / 触控板
 * 操作（没凑到 5 指）最多两行，不会刷屏，也不上手势热路径的耗时。
 */
internal object PocketGestureLog {

    /** logcat 过滤用 tag（真机排查命令：`adb logcat -s PocketGesture`）。 */
    const val TAG: String = "PocketGesture"

    /** 手势结论：凑满 5 指、放手原因、几何判定、回调触发。 */
    fun decision(message: String) {
        Log.i(TAG, message)
    }

    /** 凑指 / 跟踪过程细节：新手指落下、窗口续期、几何增量。 */
    fun trace(message: String) {
        Log.d(TAG, message)
    }

    /** 防御分支：代码认为不可能发生、但发生了就必须能看到的情况。 */
    fun warn(message: String) {
        Log.w(TAG, message)
    }
}
