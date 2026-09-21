package com.pocketkeyboard.app

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketkeyboard.app.ui.AppMode
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
 * 以及「未授予蓝牙权限不启动 HID / 授予后补启动」两条路径均不崩溃。
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

        // 导航到键盘页：87 键布局真实组合出来（抽查 q / 1 / esc 键帽）
        composeRule.onNodeWithText("键盘").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("q").onFirst().assertExists()
        composeRule.onAllNodesWithText("1").onFirst().assertExists()
        composeRule.onAllNodesWithText("esc").onFirst().assertExists()

        // 导航到触控板页：小键盘切换按钮存在，点击后进入小键盘、可再收起
        composeRule.onNodeWithText("触控板").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("打开或收起数字小键盘").assertExists()
        composeRule.onNodeWithContentDescription("打开或收起数字小键盘").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("数字小键盘").assertExists()
        composeRule.onNodeWithText("完成").performClick()
        composeRule.waitForIdle()

        // 回到配对页
        composeRule.onNodeWithText("配对").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("CONTROL").assertExists()
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
