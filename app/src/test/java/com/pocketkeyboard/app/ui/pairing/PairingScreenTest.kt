package com.pocketkeyboard.app.ui.pairing

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketkeyboard.app.ui.DevicePlatform
import com.pocketkeyboard.app.ui.MainViewModel
import com.pocketkeyboard.app.ui.PairedDevice
import com.pocketkeyboard.app.ui.theme.PocketKeyboardTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 配对页 Robolectric UI 测试（JVM 可复现）。
 *
 * 覆盖本轮两个 bug 的页面行为：
 * - **Bug 2b**：设备列表分「已配对设备 / 新发现的设备」两区，已 bond 但未选平台的设备
 *   也能看到（不再被平台过滤藏掉）；已连接 HID 对端带「已连接」标识；点新设备选完平台
 *   后移入上区。
 * - **Bug 3**：长按设备行重选平台；左滑露出删除 → 二次确认 → removeBond + 清 DataStore
 *   平台记录 + 列表刷新。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PairingScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /** 清掉 DataStore 里可能残留的平台记录（Robolectric 每个用例独立，这里只是兜底）。 */
    private fun clearPlatformStore() = runBlocking<Unit> {
        listOf(APPLE_ADDRESS, WINDOWS_ADDRESS, UNCONFIRMED_ADDRESS, DELETE_ADDRESS)
            .forEach { address -> DevicePlatformStore(context).remove(address) }
    }

    private fun viewModelWith(vararg devices: PairedDevice): MainViewModel =
        MainViewModel().apply { setPairedDevices(devices.toList()) }

    private fun setScreen(
        viewModel: MainViewModel,
        connectedAddresses: Set<String> = emptySet(),
        removeBond: (suspend (String) -> com.pocketkeyboard.app.hid.BondRemoval)? = null,
    ) {
        viewModel.setConnectedDeviceAddresses(connectedAddresses)
        composeRule.setContent {
            PocketKeyboardTheme {
                PairingScreen(
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                    removeBond = removeBond,
                )
            }
        }
        composeRule.waitForIdle()
    }

    /**
     * 在某个设备行上左滑（露出删除操作）。
     *
     * 从行的右缘按下、整体向左移动 400px（远超 88dp 露出宽度），再抬起。
     * 手势识别挂在行的外层 pointerInput 上、读 Initial pass，因此注入在文本节点上
     * 也能被行的识别层收到。
     */
    private fun swipeLeftOn(text: String) {
        composeRule.onNodeWithText(text).performTouchInput {
            down(centerRight)
            moveBy(Offset(-400f, 0f))
            advanceEventTime(50)
            up()
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `both sections are shown with platform icons`() = runBlocking<Unit> {
        clearPlatformStore()
        DevicePlatformStore(context).set(APPLE_ADDRESS, DevicePlatform.APPLE)
        DevicePlatformStore(context).set(WINDOWS_ADDRESS, DevicePlatform.OTHER)

        val viewModel = viewModelWith(
            PairedDevice(APPLE_ADDRESS, "测试 iPad", DevicePlatform.OTHER),
            PairedDevice(WINDOWS_ADDRESS, "测试 Windows", DevicePlatform.APPLE),
            // 已 bond 未选平台：上轮会被藏掉，这轮必须出现在「新发现的设备」里
            PairedDevice(UNCONFIRMED_ADDRESS, "未确认平台的设备", DevicePlatform.OTHER),
        )
        setScreen(viewModel)

        // 两个分区标题都在
        composeRule.onNodeWithText("已配对设备").assertExists()
        composeRule.onNodeWithText("新发现的设备").assertExists()

        // 已确认平台的两台设备在列表里，且各带对应平台图标
        // （ViewModel 里的 platform 是桥接探测值，展示以 DataStore 记录为准）
        composeRule.onNodeWithText("测试 iPad").assertExists()
        composeRule.onNodeWithContentDescription("苹果设备图标").assertIsDisplayed()
        composeRule.onNodeWithText("测试 Windows").assertExists()
        // 未确认平台的设备平台也是 OTHER，因此共有两个「其他设备图标」
        composeRule.onAllNodesWithContentDescription("其他设备图标").assertCountEquals(2)

        // 未确认平台的设备出现在「新发现的设备」分区，而不是被藏掉
        composeRule.onNodeWithText("未确认平台的设备").assertExists()

        // 上区有设备 → 上区不显示空态文案
        composeRule.onAllNodesWithText("设备确认平台类型后会显示在这里").assertCountEquals(0)
    }

    @Test
    fun `empty sections show their own texts`() = runBlocking<Unit> {
        clearPlatformStore()
        val viewModel = viewModelWith()
        setScreen(viewModel)

        composeRule.onNodeWithText("暂无已配对设备").assertExists()
        composeRule.onNodeWithText("暂无新发现的设备").assertExists()
    }

    @Test
    fun `connected host shows connected badge`() = runBlocking<Unit> {
        clearPlatformStore()
        DevicePlatformStore(context).set(APPLE_ADDRESS, DevicePlatform.APPLE)

        val viewModel = viewModelWith(
            PairedDevice(APPLE_ADDRESS, "测试 iPad", DevicePlatform.APPLE),
        )
        setScreen(viewModel, connectedAddresses = setOf(APPLE_ADDRESS))

        composeRule.onNodeWithText("已连接").assertExists()
    }

    @Test
    fun `selecting platform moves new device into confirmed section`() = runBlocking<Unit> {
        clearPlatformStore()
        val viewModel = viewModelWith(
            PairedDevice(UNCONFIRMED_ADDRESS, "未确认平台的设备", DevicePlatform.OTHER),
        )
        setScreen(viewModel)

        // 点新设备 → 弹平台选择
        composeRule.onNodeWithText("未确认平台的设备").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("这是苹果设备还是其他设备？").assertIsDisplayed()

        // 选「苹果设备」→ 写入 DataStore，设备移入上区（带苹果图标）
        composeRule.onNodeWithText("苹果设备").performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("这是苹果设备还是其他设备？").assertCountEquals(0)
        // 设备移入上区：上区不再空、下区变空（各自有独立空态文案）
        composeRule.onAllNodesWithText("暂无已配对设备").assertCountEquals(0)
        composeRule.onNodeWithText("暂无新发现的设备").assertExists()
        // DataStore 里记下了平台选择
        val platforms = DevicePlatformStore(context).platforms.first()
        assertEquals(DevicePlatform.APPLE, platforms[UNCONFIRMED_ADDRESS])
    }

    @Test
    fun `long press reopens platform dialog`() = runBlocking<Unit> {
        clearPlatformStore()
        DevicePlatformStore(context).set(APPLE_ADDRESS, DevicePlatform.APPLE)

        val viewModel = viewModelWith(
            PairedDevice(APPLE_ADDRESS, "测试 iPad", DevicePlatform.APPLE),
        )
        setScreen(viewModel)

        // 长按已确认设备 → 重新选择平台（文案不同，说明会影响键盘布局）
        composeRule.onNodeWithText("测试 iPad").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            up()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("重新选择平台类型").assertIsDisplayed()
        composeRule.onNodeWithText("针对「测试 iPad」重新选择平台类型，键盘布局会随之切换。")
            .assertIsDisplayed()
    }

    @Test
    fun `swipe left reveals delete and confirm removes the device`() = runBlocking<Unit> {
        clearPlatformStore()
        DevicePlatformStore(context).set(DELETE_ADDRESS, DevicePlatform.OTHER)

        val viewModel = viewModelWith(
            PairedDevice(DELETE_ADDRESS, "待删除的设备", DevicePlatform.OTHER),
        )
        val removed = mutableListOf<String>()
        setScreen(viewModel, removeBond = { address ->
            removed.add(address)
            com.pocketkeyboard.app.hid.BondRemoval.Removed
        })

        // 左滑露出删除操作
        swipeLeftOn("待删除的设备")
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("删除设备").assertIsDisplayed()

        // 点删除 → 二次确认弹窗
        composeRule.onNodeWithContentDescription("删除设备").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("解除配对？").assertIsDisplayed()

        // 确认删除：removeBond 被调用、设备从列表消失、DataStore 平台记录被清掉。
        // 删除流程是挂起的（等 bond 广播确认），因此用 waitUntil 等列表真的更新
        composeRule.onNodeWithText("解除配对").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("待删除的设备").fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitForIdle()

        assertEquals(listOf(DELETE_ADDRESS), removed)
        composeRule.onAllNodesWithText("待删除的设备").assertCountEquals(0)
        composeRule.onNodeWithText("暂无新发现的设备").assertExists()
        assertEquals(null, DevicePlatformStore(context).platforms.first()[DELETE_ADDRESS])
    }

    @Test
    fun `cancel keeps the device`() = runBlocking<Unit> {
        clearPlatformStore()
        DevicePlatformStore(context).set(DELETE_ADDRESS, DevicePlatform.OTHER)

        val viewModel = viewModelWith(
            PairedDevice(DELETE_ADDRESS, "待删除的设备", DevicePlatform.OTHER),
        )
        val removed = mutableListOf<String>()
        setScreen(viewModel, removeBond = { address ->
            removed.add(address)
            com.pocketkeyboard.app.hid.BondRemoval.Removed
        })

        swipeLeftOn("待删除的设备")
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("删除设备").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("取消").performClick()
        composeRule.waitForIdle()

        assertEquals(emptyList<String>(), removed)
        composeRule.onNodeWithText("待删除的设备").assertExists()
    }

    @Test
    fun `failed removeBond keeps the device and hints`() = runBlocking<Unit> {
        clearPlatformStore()
        DevicePlatformStore(context).set(DELETE_ADDRESS, DevicePlatform.OTHER)

        val viewModel = viewModelWith(
            PairedDevice(DELETE_ADDRESS, "待删除的设备", DevicePlatform.OTHER),
        )
        // Bug 2a：反射返回 false 不再直接判定失败，但**广播确认后**设备仍在 bonded
        // （真正失败）时必须提示失败并保留设备
        setScreen(
            viewModel,
            removeBond = {
                com.pocketkeyboard.app.hid.BondRemoval.Failed("bond still present")
            },
        )

        swipeLeftOn("待删除的设备")
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("删除设备").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("解除配对").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("待删除的设备").assertExists()
        composeRule.onNodeWithText("删除失败，请稍后再试").assertExists()
    }

    @Test
    fun `clicking confirmed device sets it as active target`() = runBlocking<Unit> {
        clearPlatformStore()
        DevicePlatformStore(context).set(APPLE_ADDRESS, DevicePlatform.APPLE)

        val viewModel = viewModelWith(
            PairedDevice(APPLE_ADDRESS, "测试 iPad", DevicePlatform.OTHER),
        )
        setScreen(viewModel)

        composeRule.onNodeWithText("测试 iPad").performClick()
        composeRule.waitForIdle()

        assertEquals(APPLE_ADDRESS, viewModel.activeDevice.value?.address)
        // 展示以 DataStore 为准：ViewModel 里的探测值是 OTHER，点完应被纠正成 APPLE
        assertEquals(DevicePlatform.APPLE, viewModel.activeDevice.value?.platform)
    }

    @Test
    fun `clicking another confirmed device switches the active target`() = runBlocking<Unit> {
        clearPlatformStore()
        DevicePlatformStore(context).set(APPLE_ADDRESS, DevicePlatform.APPLE)
        DevicePlatformStore(context).set(WINDOWS_ADDRESS, DevicePlatform.OTHER)

        val viewModel = viewModelWith(
            PairedDevice(APPLE_ADDRESS, "测试 iPad", DevicePlatform.APPLE),
            PairedDevice(WINDOWS_ADDRESS, "测试 Windows", DevicePlatform.OTHER),
        )
        viewModel.setActiveDevice(PairedDevice(APPLE_ADDRESS, "测试 iPad", DevicePlatform.APPLE))
        setScreen(viewModel)

        // Bug 2c：点已确认的另一台设备 → 控制目标切换（而不是「没反应」）
        composeRule.onNodeWithText("测试 Windows").performClick()
        composeRule.waitForIdle()

        assertEquals(WINDOWS_ADDRESS, viewModel.activeDevice.value?.address)
        // 展示以 DataStore 记录为准，而不是桥接写的探测值
        assertEquals(DevicePlatform.OTHER, viewModel.activeDevice.value?.platform)
    }

    private companion object {
        const val APPLE_ADDRESS = "AA:BB:CC:DD:EE:01"
        const val WINDOWS_ADDRESS = "AA:BB:CC:DD:EE:02"
        const val UNCONFIRMED_ADDRESS = "AA:BB:CC:DD:EE:03"
        const val DELETE_ADDRESS = "AA:BB:CC:DD:EE:04"
    }
}
