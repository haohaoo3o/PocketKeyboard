package com.pocketkeyboard.app.ui.pairing

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketkeyboard.app.ui.DevicePlatform
import com.pocketkeyboard.app.ui.MainViewModel
import com.pocketkeyboard.app.ui.PairedDevice
import com.pocketkeyboard.app.ui.theme.PocketKeyboardTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 配对页 Robolectric UI 测试（JVM 可复现）。
 *
 * 覆盖本轮改造的核心行为：设备列表只展示平台已确定的设备（DataStore 有记录），
 * 未确认的设备不出现；已确认的设备行带平台图标（苹果 / Windows 矢量）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PairingScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun deviceListShowsOnlyPlatformConfirmedDevicesWithIcons() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // DataStore 里只给 APPLE_ADDRESS 记录平台；OTHER_ADDRESS 故意不记录（平台未确定）
        runBlocking {
            DevicePlatformStore(context).set(APPLE_ADDRESS, DevicePlatform.APPLE)
            DevicePlatformStore(context).set(WINDOWS_ADDRESS, DevicePlatform.OTHER)
        }

        val viewModel = MainViewModel().apply {
            setPairedDevices(
                listOf(
                    PairedDevice(APPLE_ADDRESS, "测试 iPad", DevicePlatform.OTHER),
                    PairedDevice(WINDOWS_ADDRESS, "测试 Windows", DevicePlatform.APPLE),
                    PairedDevice(UNCONFIRMED_ADDRESS, "未确认平台的设备", DevicePlatform.OTHER),
                ),
            )
        }

        composeRule.setContent {
            PocketKeyboardTheme {
                PairingScreen(modifier = Modifier.fillMaxSize(), viewModel = viewModel)
            }
        }
        composeRule.waitForIdle()

        // 两台已确认平台的设备都在列表里，且各带对应平台图标
        // （ViewModel 里的 platform 是桥接探测值，展示以 DataStore 记录为准）
        composeRule.onNodeWithText("测试 iPad").assertExists()
        composeRule.onNodeWithContentDescription("苹果设备图标").assertExists()
        composeRule.onNodeWithText("测试 Windows").assertExists()
        composeRule.onNodeWithContentDescription("其他设备图标").assertExists()

        // 平台未确认的设备：不出现在列表里
        composeRule.onAllNodesWithText("未确认平台的设备").assertCountEquals(0)

        // 只剩未确认设备时：列表为空，空态说明「确认平台类型后显示在这里」
        viewModel.setPairedDevices(
            listOf(PairedDevice(UNCONFIRMED_ADDRESS, "未确认平台的设备", DevicePlatform.OTHER)),
        )
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("测试 iPad").assertCountEquals(0)
        composeRule.onAllNodesWithText("测试 Windows").assertCountEquals(0)
        composeRule.onNodeWithText("设备确认平台类型后会显示在这里").assertExists()
    }

    private companion object {
        const val APPLE_ADDRESS = "AA:BB:CC:DD:EE:01"
        const val WINDOWS_ADDRESS = "AA:BB:CC:DD:EE:02"
        const val UNCONFIRMED_ADDRESS = "AA:BB:CC:DD:EE:03"
    }
}
