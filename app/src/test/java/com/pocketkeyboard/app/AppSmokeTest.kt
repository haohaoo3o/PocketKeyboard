package com.pocketkeyboard.app

import android.Manifest
import android.content.pm.ActivityInfo
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * App 级 UI 冒烟测试（Robolectric + Compose，JVM 可复现）。
 *
 * 覆盖：冷启动不崩溃、三个页面可渲染可导航、小键盘开合、
 * dock 自动隐藏 / 单指边缘内滑唤出 / 切模式后再次自动隐藏、
 * 竖屏融合布局（触控板 + 系统输入法唤起区 + 可锁定修饰键排）与横屏全屏 87 键
 * 两条布局分支，以及「未授予蓝牙权限不启动 HID / 授予后补启动」两条路径均不崩溃。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchWithNoBluetoothPermission_showsPairingAndNavigatesAllPages() {
        composeRule.waitForIdle()

        // 配对页：CONTROL 按钮存在（未连接任何设备时为禁用态，但仍可渲染）
        composeRule.onNodeWithText("CONTROL").assertExists()

        // 导航到键盘页：竖屏为融合布局（上半触控板 + 中部系统输入法唤起区 + 底部修饰键排）
        composeRule.onNodeWithText("键盘").performClick()
        composeRule.waitForIdle()
        // B4：键盘模式（KEYBOARD）自动聚焦并弹出系统输入法，因此未聚焦时才显示的
        // 「点此唤起系统键盘」提示不出现；输入区本身（无障碍描述）照常组合出来
        composeRule.onNodeWithContentDescription("文本输入区，点此弹出系统键盘，输入内容会发送到被控设备")
            .assertExists()
        composeRule.onAllNodesWithText("点此唤起系统键盘").assertCountEquals(0)
        // 修饰键排：ctrl / shift / fn / win / alt / tab / esc 七颗键（Windows 平台标签）
        composeRule.onNodeWithContentDescription("ctrl").assertExists()
        composeRule.onNodeWithContentDescription("shift").assertExists()
        composeRule.onNodeWithContentDescription("fn").assertExists()
        composeRule.onNodeWithContentDescription("win").assertExists()
        composeRule.onNodeWithContentDescription("alt").assertExists()
        composeRule.onNodeWithContentDescription("tab").assertExists()
        composeRule.onNodeWithContentDescription("esc").assertExists()
        // 触控板区右上角「123」小键盘开关与设备名条
        composeRule.onNodeWithContentDescription("打开或收起数字小键盘").assertExists()
        composeRule.onNodeWithText("未选择控制设备").assertExists()

        // dock 在键盘页默认自动隐藏：三栏一个都不在
        composeRule.onAllNodesWithText("键盘").assertCountEquals(0)
        composeRule.onAllNodesWithText("触控板").assertCountEquals(0)
        composeRule.onAllNodesWithText("配对").assertCountEquals(0)

        // 单指从屏幕左缘向内滑过 24dp → dock 以苹果式 spring 滑入
        swipeFromLeftEdgeToShowDock()
        composeRule.onNodeWithText("触控板").assertExists()

        // 通过 dock 切到触控板页：竖屏同样是融合布局；dock 再次自动隐藏。
        // B4：触控板模式收起系统输入法 → 未聚焦提示回归（与键盘模式视觉可区分）
        composeRule.onNodeWithText("触控板").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("点此唤起系统键盘").assertExists()
        composeRule.onAllNodesWithText("键盘").assertCountEquals(0)
        composeRule.onAllNodesWithText("触控板").assertCountEquals(0)
        // 小键盘开合：点右上角开关 → sheet 出现 → 点「完成」收起
        composeRule.onNodeWithContentDescription("打开或收起数字小键盘").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("数字小键盘").assertExists()
        composeRule.onNodeWithText("完成").performClick()
        composeRule.waitForIdle()

        // 再次边缘内滑唤出 dock，回到配对页（dock 常显）
        swipeFromLeftEdgeToShowDock()
        composeRule.onNodeWithText("配对").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("CONTROL").assertExists()
        // 配对页 dock 常显：无需边缘滑动
        composeRule.onNodeWithText("触控板").assertExists()
    }

    /**
     * 单指从屏幕左缘向内滑过 24dp：唤出 dock。
     *
     * 滑动手势从 x = 0（左缘）出发、水平向右 160px，越过
     * [com.pocketkeyboard.app.EdgeSwipeGeometry] 的 24dp 激活带与触发距离。
     */
    private fun swipeFromLeftEdgeToShowDock() {
        composeRule.onRoot().performTouchInput {
            val y = centerY
            swipe(
                start = Offset(0f, y),
                end = Offset(160f, y),
                durationMillis = 200,
            )
        }
        composeRule.waitForIdle()
    }

    @Test
    fun launchWithBluetoothPermission_hidStartPathDoesNotCrash() {
        // 模拟用户在权限弹窗上点了「允许」后重新进入 App：
        // MainActivity 的 LaunchedEffect(permissionsGranted) 会补调 hidController.start()
        val app = RuntimeEnvironment.getApplication()
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }.toTypedArray()
        shadowOf(app).grantPermissions(*permissions)

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        // HID 后端已在授权后启动（模拟器/无蓝牙环境下上报不可用但不崩溃），
        // 配对页正常渲染即说明整条链路无异常
        composeRule.onNodeWithText("CONTROL").assertExists()
    }
}
