package com.pocketkeyboard.app.ui.pairing

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.util.Log
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketkeyboard.app.R
import com.pocketkeyboard.app.hid.BondRemoval
import com.pocketkeyboard.app.hid.HidController
import com.pocketkeyboard.app.hid.HidStatusText
import com.pocketkeyboard.app.hid.removeBondAndConfirm
import com.pocketkeyboard.app.ui.AppMode
import com.pocketkeyboard.app.ui.DevicePlatform
import com.pocketkeyboard.app.ui.MainViewModel
import com.pocketkeyboard.app.ui.PairedDevice
import com.pocketkeyboard.app.ui.theme.MinimalGray
import com.pocketkeyboard.app.ui.theme.PureBlack
import com.pocketkeyboard.app.ui.theme.PureWhite
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/** 配对码位数：6 位数字。 */
private const val PIN_LENGTH = 6

/** 配对页日志 TAG（删除流程等需要在真机上定位的路径）。 */
private const val TAG = "PairingScreen"

/** 「可被发现」胶囊按钮的可视高度（dp）：文字用 bodySmall 档，固定高度 + 垂直居中不裁切。 */
private const val DISCOVERABLE_BUTTON_HEIGHT = 40

/** 「可被发现」按钮在可视高度之外额外外扩的热区（dp）：40 + 4×2 = 48dp 最小触控目标。 */
private const val DISCOVERABLE_TOUCH_OVERHANG = 4

/** 平台图标尺寸（dp）：20–24dp 区间内，与设备名首行基线对齐。 */
private const val PLATFORM_ICON_SIZE = 22


/** CONTROL 按钮按下时的缩放（苹果式按压手感）。 */
private const val PRESSED_SCALE = 0.97f

/** 禁用态文字透明度。 */
private const val DISABLED_ALPHA = 0.35f

/** 「请先配对」提示的自动消失时长。 */
private const val CONTROL_HINT_MILLIS = 2400L

/** 删除失败提示的自动消失时长。 */
private const val DELETE_HINT_MILLIS = 2400L

/** 侧滑露出的删除操作宽度（dp）：容纳「删除」胶囊 + 触控余量。 */
private val SWIPE_REVEAL_WIDTH = 88.dp

/**
 * 删除操作的警示色（苹果系统红 FF3B30）。
 *
 * 配对页整体是纯黑 / 纯白语言，删除是唯一需要「危险」语义的操作，用一点红让用户
 * 在侧滑露出的瞬间就认出这行会被移除；不放 ui/theme 是为了不污染全局色板
 * （本文件是唯一使用者）。
 */
private val DestructiveRed = Color(0xFFFF3B30)

/**
 * 配对页：展示本机蓝牙名称与 6 位数字配对码，管理已配对设备与控制目标。
 *
 * 页面不自己持有任何跨页状态：配对码 / 设备列表 / 当前控制目标 / 页面模式全部来自
 * [MainViewModel]（跨模块契约），本页只负责展示与写回；进入键盘页只通过
 * `viewModel.setMode(AppMode.KEYBOARD)` 切换，不写死页面跳转。
 *
 * ## 设备列表分区（Bug 2b）
 * 已配对设备分两个区：
 * 1. **已配对设备**：平台已确认（DataStore 有记录）——苹果 / Windows 图标 + 名称，
 *    点击切换控制目标；
 * 2. **新发现的设备**：系统已 bond 但还没选平台——点击弹平台选择，选完移入上区。
 *
 * 这样「用户在系统设置里 bond 了新设备」回到 App 也能立刻看到（不再被平台过滤藏掉），
 * 同时不会把未确认平台的设备误显示成 Windows 布局。
 *
 * ## 行内交互（Bug 3）
 * - 点击：已确认设备切换控制目标；未确认设备弹平台选择；
 * - 长按：重新弹平台选择（苹果 / 其他），写 DataStore 后键盘布局随之切换；
 * - 左滑：露出「删除」→ 二次确认 → `removeBond()`（隐藏 API 反射）+ 清 DataStore
 *   平台记录 + 刷新列表。带触觉反馈。
 *
 * @param hidController MainActivity 持有的唯一 HID 后端。选中设备时除写 ViewModel 外
 *    还要调 `hidController.setActiveDevice(address)`，把传输层的控制目标（registry）
 *    一起切过去，否则 UI 显示已切换、HID 报告却仍发给上一个设备。为 null 时（例如
 *     预览 / 单测）只写 ViewModel。
 * @param removeBond 解除系统配对的注入点；null = 系统实现（hid 层反射
 *     `BluetoothDevice.removeBond()` + 等 `ACTION_BOND_STATE_CHANGED → BOND_NONE`
 *     广播确认，见 [removeBondAndConfirm]）。单测传假实现（返回 [BondRemoval]）
 *     即可验证「删除成功 / 删除失败」两条 UI 流程，不必依赖 Robolectric 蓝牙桩。
 */
@Composable
fun PairingScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = viewModel(),
    hidController: HidController? = null,
    removeBond: (suspend (String) -> BondRemoval)? = null,
) {
    val context = LocalContext.current
    // 删除的成败判据是 bond 广播（见 removeBondAndConfirm），因此整个删除流程是
    // 挂起函数：确认弹窗的 onConfirm 里起协程等待，期间弹窗已关闭、列表保持原样，
    // 出结果后再决定摘行还是提示失败。
    // 注意：elvis 右侧必须先落到「显式声明为挂起函数类型」的局部变量上，
    // 否则 lambda 会被推断成普通 Function1、无法调用挂起的 removeBondedDevice。
    val removeBondAction: suspend (String) -> BondRemoval = remember(context) {
        val systemImplementation: suspend (String) -> BondRemoval =
            { address -> removeBondedDevice(context, address) }
        removeBond ?: systemImplementation
    }
    val platformStore = remember { DevicePlatformStore(context) }
    val scope = rememberCoroutineScope()

    val platforms by platformStore.platforms.collectAsStateWithLifecycle(initialValue = emptyMap())
    val pairedDevices by viewModel.pairedDevices.collectAsStateWithLifecycle()
    val activeDevice by viewModel.activeDevice.collectAsStateWithLifecycle()
    val pinCode by viewModel.pinCode.collectAsStateWithLifecycle()
    val connectedAddresses by viewModel.connectedDeviceAddresses.collectAsStateWithLifecycle()
    // A2：主动连接的行内状态（连接中 / 连接失败）——点击设备行后立刻有可见反馈
    val connectingAddress by viewModel.connectingDeviceAddress.collectAsStateWithLifecycle()
    val connectFailedAddress by viewModel.connectFailedAddress.collectAsStateWithLifecycle()

    var localBluetooth by remember { mutableStateOf(LocalBluetoothState()) }
    // 未确认平台设备首次点击 → 选平台；长按 → 重选平台；左滑 → 删除确认
    var pendingDevice by remember { mutableStateOf<PairedDevice?>(null) }
    var reselectDevice by remember { mutableStateOf<PairedDevice?>(null) }
    var pendingDelete by remember { mutableStateOf<PairedDevice?>(null) }
    var deleteFailed by remember { mutableStateOf(false) }
    var showControlHint by remember { mutableStateOf(false) }
    var discoverableSeconds by remember { mutableIntStateOf(0) }

    // 配对流程需要一个 6 位数字配对码；ViewModel 里还没有时由本页生成并回写，
    // 之后一律以 viewModel.pinCode 为唯一来源展示。
    LaunchedEffect(Unit) {
        if (viewModel.pinCode.value.isNullOrBlank()) {
            viewModel.setPinCode(randomPinCode())
        }
    }

    // 权限申请时机：进入配对页即补齐「读本机蓝牙信息」所需权限（MainActivity 已在启动时
    // 请求过整组蓝牙权限，这里只兜底处理被拒绝 / 尚未授予的情况）。
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        localBluetooth = loadLocalBluetooth(context, platforms)
    }
    LaunchedEffect(Unit) {
        val missing = bluetoothReadPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) !=
                PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            // 首屏就用 DataStore 里已持久化的平台标注种子化：若传空 map，
            // 系统 bonded 设备会全部落成 OTHER，要等 platforms 流第二次 emit 才修正，
            // 窗口期内用户点一个已存过「苹果」的设备会再弹一次平台选择框
            localBluetooth = loadLocalBluetooth(context, platforms)
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // Bug 2b：系统 bonded 列表随生命周期刷新。原来只在进入页面时读一次，用户在系统
    // 设置里 bond 新设备后回到 App 看不到；ON_START 时重新读一次即可（用户从系统设置
    // 返回必然经过 ON_START）。刷新的结果同时喂给下面的合并 Effect。
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        localBluetooth = loadLocalBluetooth(context, platforms)
    }

    // 设备列表以 viewModel.pairedDevices 为唯一来源；系统 bonded 设备（localBluetooth）
    // 与 HID 后端上报的列表（bridge 写进 ViewModel）两条来源在这里合并去重，
    // 并用 DataStore 中持久化的平台类型校正（不覆盖其他模块已写入的平台信息）。
    //
    // 回写策略：这里刻意回写全量合并结果、不按平台确认状态过滤。pairedDevices 是跨模块
    // 契约——MainActivity 的五指横滑循环切设备、HID bridge 的 onPairedDevicesChanged
    // 都会写它；若在这里按平台过滤回写，bridge 每次上报都会把设备重新写回来、本 Effect
    // 再过滤掉，形成来回覆盖；全部设备都未确认时合并结果还是空列表，写回去会把契约数据
    // 清空。分区只做在下方 DeviceSections 调用处的展示层，不动契约本身；
    // 写入前的 `merged != 现值` 相等性检查保证本 Effect 不会自我触发形成死循环。
    LaunchedEffect(platforms, localBluetooth.devices, pairedDevices) {
        val merged = (pairedDevices + localBluetooth.devices)
            .distinctBy { it.address }
            .map { device ->
                device.copy(platform = platforms[device.address] ?: device.platform)
            }
        if (merged != viewModel.pairedDevices.value) {
            viewModel.setPairedDevices(merged)
        }
    }

    // 「请先配对」提示出现 2.4 秒后自动淡出。
    LaunchedEffect(showControlHint) {
        if (showControlHint) {
            delay(CONTROL_HINT_MILLIS)
            showControlHint = false
        }
    }

    // 「删除失败」提示同样 2.4 秒后淡出。
    LaunchedEffect(deleteFailed) {
        if (deleteFailed) {
            delay(DELETE_HINT_MILLIS)
            deleteFailed = false
        }
    }

    val discoverableLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        discoverableSeconds = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getIntExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 0) ?: 0
        } else {
            0
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp),
    ) {
        Text(
            text = stringResource(R.string.pairing_title),
            style = MaterialTheme.typography.titleLarge,
            color = PureWhite,
        )
        Spacer(modifier = Modifier.height(12.dp))

        // HID 后端不可用提示：`MainViewModelStatusBridge.unavailableReason` 一直没接 UI，
        // 权限被拒 / 蓝牙未开 / profile 被 ROM 禁用时用户只看到「点了没反应」却看不到
        // 原因。注册成功后 bridge 自动清掉本提示（见 bridge.onAppRegistrationChanged）。
        hidController?.let { controller ->
            val unavailableReason by controller.unavailableReason
                .collectAsStateWithLifecycle(initialValue = null)
            unavailableReason?.let { reason ->
                HidUnavailableBanner(
                    message = stringResource(HidStatusText.unavailableMessage(reason)),
                )
            }
        }

        PairingHeader(
            localBluetooth = localBluetooth,
            discoverableSeconds = discoverableSeconds,
            onMakeDiscoverable = { requestDiscoverable(context, discoverableLauncher) },
            onRequestPermission = {
                permissionLauncher.launch(bluetoothReadPermissions().toTypedArray())
            },
        )

        // 配对码：页面中央
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            PinCodeBlock(pinCode = pinCode)
        }

        // 设备列表分区：已配对（平台已确认）/ 新发现（已 bond 未选平台）。
        // pairedDevices 里保留全部 bonded 设备（契约要求 + 五指横滑循环要用），
        // 「是否已确认平台」只作为分区依据，不再把设备藏起来。
        DeviceSections(
            confirmedDevices = pairedDevices.filter { platforms[it.address] != null },
            newDevices = pairedDevices.filter { platforms[it.address] == null },
            activeDevice = activeDevice,
            connectedAddresses = connectedAddresses,
            connectingAddress = connectingAddress,
            connectFailedAddress = connectFailedAddress,
            onDeviceClick = { device ->
                val knownPlatform = platforms[device.address]
                if (knownPlatform == null) {
                    // 首次连接该设备：先问平台，结果写入 DataStore，之后不再询问
                    pendingDevice = device
                } else {
                    // 先同步传输层控制目标，再写 ViewModel：registry 认识该地址时
                    // setActiveDevice 会经 bridge 回写一次（平台是探测值），随后这次
                    // 写用以 DataStore 为准的平台覆盖回来，保证展示与布局正确
                    hidController?.setActiveDevice(device.address)
                    viewModel.setActiveDevice(device.copy(platform = knownPlatform))
                    // A2：选中即主动发起连接（设备侧 HID L2CAP），UI 立刻显示
                    // 「连接中…」；已连接的设备不重复发起
                    if (device.address !in connectedAddresses) {
                        hidController?.connectHost(device.address)
                    }
                }
            },
            onDeviceLongClick = { device -> reselectDevice = device },
            onDeviceDelete = { device -> pendingDelete = device },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 症状 1：CONTROL 按钮只要选中了设备就可点（进入键盘页），连接状态不再
        // 拦截进入，另用下方文案显示——灰按钮不该是唯一的状态信号。
        ControlButton(
            enabled = activeDevice != null,
            onClick = {
                if (activeDevice != null) {
                    // 键盘页入口只通过 MainViewModel 切换 mode
                    viewModel.setMode(AppMode.KEYBOARD)
                } else {
                    showControlHint = true
                }
            },
        )

        // 连接状态文案：与设备行 chip 同源（connectedDeviceAddresses / connecting /
        // connectFailed），选中设备后即使还没连上也能看到正在发生什么。
        val controlTarget = activeDevice
        val controlStatusRes = when {
            controlTarget == null -> R.string.pairing_control_status_none
            controlTarget.address in connectedAddresses ->
                R.string.pairing_control_status_connected
            controlTarget.address == connectingAddress ->
                R.string.pairing_control_status_connecting
            controlTarget.address == connectFailedAddress ->
                R.string.pairing_control_status_failed
            else -> R.string.pairing_control_status_not_connected
        }
        Text(
            text = stringResource(controlStatusRes),
            style = MaterialTheme.typography.bodyMedium,
            color = PureWhite.copy(alpha = 0.55f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        )

        ControlHint(visible = showControlHint)
        DeleteHint(visible = deleteFailed)
        Spacer(modifier = Modifier.height(8.dp))

        // 五指挥势说明：新用户在这里第一次学到手势
        GestureHint(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        )
    }

    // 首次连接某设备时的平台选择弹窗
    pendingDevice?.let { device ->
        PlatformDialog(
            title = stringResource(R.string.pairing_platform_dialog_title),
            message = stringResource(R.string.pairing_platform_dialog_message, device.name),
            onSelect = { platform ->
                scope.launch { platformStore.set(device.address, platform) }
                // 先同步传输层控制目标，再写 ViewModel（理由同设备列表点击）
                hidController?.setActiveDevice(device.address)
                viewModel.setActiveDevice(device.copy(platform = platform))
                viewModel.setPairedDevices(
                    viewModel.pairedDevices.value.map { existing ->
                        if (existing.address == device.address) {
                            existing.copy(platform = platform)
                        } else {
                            existing
                        }
                    },
                )
                // A2：首次选完平台同样主动发起连接（已连接的不重复发起）
                if (device.address !in connectedAddresses) {
                    hidController?.connectHost(device.address)
                }
                pendingDevice = null
            },
            onDismiss = { pendingDevice = null },
        )
    }

    // 长按设备行：重新选择平台（键盘布局随之切换）
    reselectDevice?.let { device ->
        PlatformDialog(
            title = stringResource(R.string.pairing_platform_dialog_reselect_title),
            message = stringResource(
                R.string.pairing_platform_dialog_reselect_message,
                device.name,
            ),
            onSelect = { platform ->
                scope.launch { platformStore.set(device.address, platform) }
                // 重选只改平台，不抢控制目标；但若它就是当前目标，同步过去让键盘页换布局
                viewModel.setPairedDevices(
                    viewModel.pairedDevices.value.map { existing ->
                        if (existing.address == device.address) {
                            existing.copy(platform = platform)
                        } else {
                            existing
                        }
                    },
                )
                if (viewModel.activeDevice.value?.address == device.address) {
                    viewModel.setActiveDevice(device.copy(platform = platform))
                }
                reselectDevice = null
            },
            onDismiss = { reselectDevice = null },
        )
    }

    // 侧滑删除的二次确认：removeBond 之后必须由用户去对端蓝牙菜单重新配对，
    // Snackbar 撤销无法真正恢复（App 不能代替用户重走对端确认），因此用确认弹窗
    pendingDelete?.let { device ->
        DeleteConfirmDialog(
            deviceName = device.name,
            onConfirm = {
                Log.i(TAG, "delete confirmed for ${device.address}")
                pendingDelete = null
                scope.launch {
                    Log.i(TAG, "delete coroutine started for ${device.address}")
                    val outcome = removeBondAction(device.address)
                    Log.i(TAG, "bond removal for ${device.address}: $outcome")
                    when (outcome) {
                        is BondRemoval.Failed -> {
                            // 反射被拒 / 权限不足 / 广播确认设备仍在 bonded：
                            // 保留设备与平台记录，提示失败
                            deleteFailed = true
                        }

                        is BondRemoval.Removed -> {
                            Log.i(TAG, "delete removed ${device.address}")
                            // 解除成功：**先**本地摘掉该设备（不等磁盘 IO，UI 立刻一致），
                            // **再**清 DataStore 平台记录（再次 bond 时重新询问平台）。
                            // 摘行不依赖 bond 广播：HID 后端被注销 / 方案 B 空实现时
                            // 收不到 ACTION_BOND_STATE_CHANGED，UI 也要立即一致
                            viewModel.setPairedDevices(
                                viewModel.pairedDevices.value
                                    .filterNot { it.address == device.address },
                            )
                            if (viewModel.activeDevice.value?.address == device.address) {
                                viewModel.setActiveDevice(null)
                            }
                            viewModel.setConnectedDeviceAddresses(
                                viewModel.connectedDeviceAddresses.value - device.address,
                            )
                            hidController?.disconnectHost(device.address)
                            localBluetooth = loadLocalBluetooth(context, platforms)
                            platformStore.remove(device.address)
                        }
                    }
                }
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/** 生成 6 位数字配对码。 */
private fun randomPinCode(): String =
    Random.nextInt(from = 100000, until = 1000000).toString()

/** 顶部：本机蓝牙名称 + 可被发现入口。 */
@Composable
private fun PairingHeader(
    localBluetooth: LocalBluetoothState,
    discoverableSeconds: Int,
    onMakeDiscoverable: () -> Unit,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.pairing_local_name_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = PureWhite.copy(alpha = 0.5f),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when {
                        !localBluetooth.available ->
                            stringResource(R.string.pairing_bluetooth_unavailable)

                        localBluetooth.name != null -> localBluetooth.name
                        else -> stringResource(R.string.pairing_local_name_unknown)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = PureWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            // 「可被发现」胶囊按钮：文案降到 bodySmall 档、宽度 wrap content 并带
            // 足够水平内边距（不再贴边），固定 40dp 高度由 Box 垂直居中不裁切；
            // 视觉之外再外扩上下各 4dp 透明热区，点击热区达到 48dp。
            AppleButton(
                text = stringResource(R.string.pairing_make_discoverable),
                onClick = onMakeDiscoverable,
                enabled = localBluetooth.available && localBluetooth.permissionGranted,
                modifier = Modifier.height(DISCOVERABLE_BUTTON_HEIGHT.dp),
                shape = RoundedCornerShape(percent = 50),
                containerColor = Color.Transparent,
                contentColor = PureWhite.copy(alpha = 0.85f),
                borderColor = PureWhite.copy(alpha = 0.35f),
                textStyle = MaterialTheme.typography.bodySmall,
                contentPadding = PaddingValues(horizontal = 18.dp),
                touchPadding = PaddingValues(vertical = DISCOVERABLE_TOUCH_OVERHANG.dp),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (discoverableSeconds > 0) {
                stringResource(R.string.pairing_discoverable_status_on, discoverableSeconds)
            } else {
                stringResource(R.string.pairing_discoverable_status_off)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = PureWhite.copy(alpha = 0.45f),
        )
        if (!localBluetooth.permissionGranted) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.pairing_permission_needed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PureWhite.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRequestPermission) {
                    Text(
                        text = stringResource(R.string.pairing_permission_action),
                        color = PureWhite,
                    )
                }
            }
        }
    }
}

/** 页面中央：6 位数字配对码。 */
@Composable
private fun PinCodeBlock(pinCode: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.pairing_pin_label),
            style = MaterialTheme.typography.labelLarge,
            color = PureWhite.copy(alpha = 0.5f),
            letterSpacing = 2.sp,
        )
        Spacer(modifier = Modifier.height(20.dp))
        if (pinCode.isNullOrBlank()) {
            Text(
                text = stringResource(R.string.pairing_pin_placeholder),
                style = MaterialTheme.typography.displaySmall.copy(fontSize = 52.sp),
                color = PureWhite.copy(alpha = 0.3f),
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                pinCode.take(PIN_LENGTH).forEach { digit ->
                    Text(
                        text = digit.toString(),
                        style = MaterialTheme.typography.displaySmall.copy(fontSize = 52.sp),
                        color = PureWhite,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.pairing_pin_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = PureWhite.copy(alpha = 0.55f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

/**
 * 设备列表两个分区（Bug 2b）。
 *
 * - **已配对设备**：平台已确认（DataStore 有记录），苹果 / Windows 图标 + 名称，
 *   点击切换控制目标；HID registry 里已连接的对端额外带「已连接」标识；
 * - **新发现的设备**：系统已 bond 但平台未确认（例如用户在系统设置里刚配对的 Mac），
 *   点击弹平台选择，选完移入上区。
 *
 * 两个分区各有独立空态文案：上区空而下区有设备时说清「选完平台会移到这里」，
 * 下区空时说「暂无新发现的设备」。
 *
 * @param onDeviceClick 点击行（已确认 = 切换控制目标；未确认 = 弹平台选择）
 * @param onDeviceLongClick 长按行（重新选择平台）
 * @param onDeviceDelete 侧滑露出删除并确认后回调（removeBond + 清 DataStore）
 * @param connectingAddress 正在主动连接的地址（A2：行内「连接中…」）
 * @param connectFailedAddress 最近一次主动连接下发失败的地址（A2：行内「连接失败」）
 */
@Composable
private fun DeviceSections(
    confirmedDevices: List<PairedDevice>,
    newDevices: List<PairedDevice>,
    activeDevice: PairedDevice?,
    connectedAddresses: Set<String>,
    connectingAddress: String?,
    connectFailedAddress: String?,
    onDeviceClick: (PairedDevice) -> Unit,
    onDeviceLongClick: (PairedDevice) -> Unit,
    onDeviceDelete: (PairedDevice) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        DeviceSection(
            title = stringResource(R.string.pairing_devices_title),
            emptyText = if (newDevices.isEmpty()) {
                stringResource(R.string.pairing_devices_empty)
            } else {
                stringResource(R.string.pairing_devices_empty_unconfirmed)
            },
            devices = confirmedDevices,
            activeDevice = activeDevice,
            connectedAddresses = connectedAddresses,
            connectingAddress = connectingAddress,
            connectFailedAddress = connectFailedAddress,
            onDeviceClick = onDeviceClick,
            onDeviceLongClick = onDeviceLongClick,
            onDeviceDelete = onDeviceDelete,
        )
        Spacer(modifier = Modifier.height(20.dp))
        DeviceSection(
            title = stringResource(R.string.pairing_new_devices_title),
            emptyText = stringResource(R.string.pairing_new_devices_empty),
            devices = newDevices,
            activeDevice = activeDevice,
            connectedAddresses = connectedAddresses,
            connectingAddress = connectingAddress,
            connectFailedAddress = connectFailedAddress,
            onDeviceClick = onDeviceClick,
            onDeviceLongClick = onDeviceLongClick,
            onDeviceDelete = onDeviceDelete,
        )
    }
}

/** 单个分区：标题 + 设备行 / 空态文案。 */
@Composable
private fun DeviceSection(
    title: String,
    emptyText: String,
    devices: List<PairedDevice>,
    activeDevice: PairedDevice?,
    connectedAddresses: Set<String>,
    connectingAddress: String?,
    connectFailedAddress: String?,
    onDeviceClick: (PairedDevice) -> Unit,
    onDeviceLongClick: (PairedDevice) -> Unit,
    onDeviceDelete: (PairedDevice) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = PureWhite.copy(alpha = 0.5f),
            letterSpacing = 2.sp,
        )
        Spacer(modifier = Modifier.height(10.dp))
        if (devices.isEmpty()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = PureWhite.copy(alpha = 0.4f),
                modifier = Modifier.padding(vertical = 12.dp),
            )
            return@Column
        }
        devices.forEach { device ->
            DeviceRow(
                device = device,
                selected = device.address == activeDevice?.address,
                connected = device.address in connectedAddresses,
                connecting = device.address == connectingAddress,
                connectFailed = device.address == connectFailedAddress,
                onClick = { onDeviceClick(device) },
                onLongClick = { onDeviceLongClick(device) },
                onDelete = { onDeviceDelete(device) },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * 单个设备行：平台图标 + 设备名 + 平台类型 + 角标，支持左滑删除与长按重选平台（Bug 3）。
 *
 * 选中态加强（症状 1）：当前控制目标带**白色描边** + 「当前控制目标」小标，点击后
 * 即使连接还没建立也能从行上看到「连接中… → 已连接 / 连接失败」的流转——不再出现
 * 「点了没反应」。
 *
 * ## 手势仲裁（与 MainActivity 边缘滑激活层同一套思路）
 * 侧滑识别挂在**外层**、读 [PointerEventPass.Initial]（父先于子），只在横向位移超过
 * 系统 touchSlop 后才消费事件：未超阈值时什么都不消费，内层 `combinedClickable` 的
 * 点击 / 长按照常工作；一旦成为拖动就消费全部 change，内层再也看不到这些移动，
 * 因此侧滑不会顺带触发点击或长按。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceRow(
    device: PairedDevice,
    selected: Boolean,
    connected: Boolean,
    connecting: Boolean,
    connectFailed: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val revealWidthPx = with(LocalDensity.current) { SWIPE_REVEAL_WIDTH.toPx() }
    val haptics = LocalHapticFeedback.current
    var revealed by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var thresholdHapticFired by remember { mutableStateOf(false) }
    val settledOffset by animateFloatAsState(
        targetValue = if (revealed) -revealWidthPx else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "deviceRowSwipeOffset",
    )
    // 拖动过程中跟手（dragOffset），松手后由 spring 动画收起到 0 或 -revealWidthPx
    val offsetX = if (dragging) dragOffset else settledOffset

    Box(modifier = modifier.fillMaxWidth()) {
        // 底层：删除操作，铺满行高、右对齐
        Row(
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(14.dp))
                .background(DestructiveRed)
                .padding(end = 2.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DeleteAction(
                // 露出过半才算「可点删除」，没露够时点到的是被行内容盖住的区域
                enabled = DeviceRowSwipe.shouldReveal(offsetX, revealWidthPx),
                label = stringResource(R.string.pairing_device_delete),
                description = stringResource(R.string.pairing_device_delete_description),
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDelete()
                },
            )
        }
        // 前景：行内容，跟手横移
        Row(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MinimalGray)
                // 选中态加强：当前控制目标加白色描边（症状 1：一眼看到选中了谁）
                .then(
                    if (selected) {
                        Modifier.border(
                            width = 1.5.dp,
                            color = PureWhite,
                            shape = RoundedCornerShape(14.dp),
                        )
                    } else {
                        Modifier
                    },
                )
                .pointerInput(revealWidthPx) {
                    val touchSlop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        var totalX = 0f
                        var gestureActive = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) break
                            totalX += change.position.x - change.previousPosition.x
                            if (!gestureActive && abs(totalX) > touchSlop) {
                                gestureActive = true
                                dragging = true
                                thresholdHapticFired = false
                                // 进入拖动的手感：让用户立刻知道这行现在可删
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            if (gestureActive) {
                                // 只允许向左滑露出删除，右滑 / 回滑都夹在 [-reveal, 0]
                                dragOffset = DeviceRowSwipe.clampOffset(totalX, revealWidthPx)
                                val pastThreshold = DeviceRowSwipe.shouldReveal(
                                    dragOffset,
                                    revealWidthPx,
                                )
                                if (pastThreshold && !thresholdHapticFired) {
                                    thresholdHapticFired = true
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                } else if (!pastThreshold) {
                                    thresholdHapticFired = false
                                }
                                // 消费全部 change：内层点击 / 长按识别看不到这些移动，
                                // 侧滑因此不会顺带触发点击或长按
                                event.changes.forEach { it.consume() }
                            }
                        }
                        if (gestureActive) {
                            dragging = false
                            revealed = DeviceRowSwipe.shouldReveal(dragOffset, revealWidthPx)
                        }
                    }
                }
                .combinedClickable(
                    onClick = {
                        // 已露出删除操作时，先点一次收起，避免误触「切换控制目标」
                        if (revealed) revealed = false else onClick()
                    },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    },
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            // 顶端对齐：22dp 平台图标与设备名首行文字基线对齐（行内还有一行平台小字，
            // 居中对齐会把图标压到两行文字的中间，低于设备名）
            verticalAlignment = Alignment.Top,
        ) {
            PlatformIcon(
                platform = device.platform,
                contentDescription = stringResource(platformIconDescription(device.platform)),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = PureWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = platformLabel(device.platform),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PureWhite.copy(alpha = 0.5f),
                )
            }
            // 角标列：「已连接 / 连接中… / 连接失败」（A2 状态流转）+「当前控制目标」
            if (connected || connecting || connectFailed || selected) {
                Spacer(modifier = Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    when {
                        connected -> Badge(text = stringResource(R.string.pairing_device_connected))
                        connecting -> Badge(
                            text = stringResource(R.string.pairing_device_connecting),
                            contentColor = PureWhite.copy(alpha = 0.75f),
                            borderColor = PureWhite.copy(alpha = 0.4f),
                        )
                        connectFailed -> Badge(
                            text = stringResource(R.string.pairing_device_connect_failed),
                            contentColor = DestructiveRed,
                            borderColor = DestructiveRed.copy(alpha = 0.6f),
                        )
                    }
                    if (connected || connecting || connectFailed) {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    if (selected) {
                        Badge(text = stringResource(R.string.pairing_device_active))
                    }
                }
            }
        }
    }
}

/** 设备行右下角的小角标（已连接 / 连接中 / 连接失败 / 当前控制目标）。 */
@Composable
private fun Badge(
    text: String,
    modifier: Modifier = Modifier,
    contentColor: Color = PureWhite.copy(alpha = 0.75f),
    borderColor: Color = PureWhite.copy(alpha = 0.4f),
) {
    Box(
        modifier = modifier
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(percent = 50),
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1,
        )
    }
}

/** 侧滑露出的删除操作：红色底 + 白色「删除」胶囊。 */
@Composable
private fun DeleteAction(
    enabled: Boolean,
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(SWIPE_REVEAL_WIDTH)
            .clickable(enabled = enabled, onClick = onClick)
            // 读屏描述：两个字对 TalkBack 太短，说明白这是「删除设备」
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = PureWhite,
        )
    }
}

/** 平台类型文案。 */
@Composable
private fun platformLabel(platform: DevicePlatform): String = when (platform) {
    DevicePlatform.APPLE -> stringResource(R.string.pairing_device_platform_apple)
    DevicePlatform.OTHER -> stringResource(R.string.pairing_device_platform_other)
}

/** 平台图标的读屏描述（TalkBack 与 UI 测试用；图标本身不识字）。 */
private fun platformIconDescription(platform: DevicePlatform): Int = when (platform) {
    DevicePlatform.APPLE -> R.string.pairing_platform_icon_apple
    DevicePlatform.OTHER -> R.string.pairing_platform_icon_other
}

/**
 * CONTROL 大按钮（症状 1）。
 *
 * 只要选中了设备就可点（enabled = 有控制目标），点击即进入键盘页；**连接状态不再
 * 拦截进入**，另由按钮下方的状态文案与设备行角标显示——灰按钮不该是唯一的状态信号。
 * 未选设备时仍接收点击，用于给出「请先选择设备」提示。
 */
@Composable
private fun ControlButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppleButton(
        text = stringResource(R.string.pairing_control),
        // 始终接收点击：未选设备时点击用于给出「请先配对」提示
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(66.dp),
        shape = RoundedCornerShape(20.dp),
        containerColor = if (enabled) PureBlack else MinimalGray,
        contentColor = if (enabled) PureWhite else PureWhite.copy(alpha = DISABLED_ALPHA),
        borderColor = if (enabled) PureWhite else null,
        textStyle = MaterialTheme.typography.titleLarge.copy(letterSpacing = 4.sp),
    )
}

/**
 * HID 后端不可用提示条（配对页顶部）。
 *
 * 配色沿用纯黑 / 纯白语言 + 删除操作的警示红，与页面里其它内联提示同一层级；
 * 出现即代表「现在连不了」，注册成功后由 bridge 清掉。
 */
@Composable
private fun HidUnavailableBanner(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        shape = RoundedCornerShape(14.dp),
        color = DestructiveRed.copy(alpha = 0.12f),
        border = BorderStroke(width = 1.dp, color = DestructiveRed.copy(alpha = 0.5f)),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = PureWhite.copy(alpha = 0.85f),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/** 未连接设备时点击 CONTROL 的内联提示。 */
@Composable
private fun ColumnScope.ControlHint(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = visible, modifier = modifier) {
        Text(
            text = stringResource(R.string.pairing_control_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = PureWhite.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        )
    }
}

/** 删除设备失败（removeBond 反射被拒 / 权限不足）时的内联提示。 */
@Composable
private fun ColumnScope.DeleteHint(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = visible, modifier = modifier) {
        Text(
            text = stringResource(R.string.pairing_delete_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = DestructiveRed,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        )
    }
}

/**
 * 苹果式按钮：无涟漪，按下缩放到 [PRESSED_SCALE]，松开以弹性弹簧回弹。
 *
 * 分内外两层：外层只承担点击热区（[touchPadding] 外扩出的部分完全透明，
 * 视觉不变），内层是可见的胶囊 / 圆角矩形（背景、边框、按压缩放都在内层）。
 * 这样可以在不改变外观的前提下把任意小按钮的热区扩大到 48dp 最小触控目标。
 *
 * @param contentPadding 可视层的水平 / 垂直内边距（文案不再贴边）。
 * @param touchPadding 热区在可视层之外的外扩量，不产生任何视觉变化。
 */
@Composable
private fun AppleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(20.dp),
    containerColor: Color = PureBlack,
    contentColor: Color = PureWhite,
    borderColor: Color? = null,
    textStyle: TextStyle = MaterialTheme.typography.titleLarge,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp),
    touchPadding: PaddingValues = PaddingValues(0.dp),
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) PRESSED_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "appleButtonPressScale",
    )
    // 外层：热区层。padding 出的区域透明，点击与按压判定都发生在这一层。
    Box(
        modifier = Modifier
            .padding(touchPadding)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 内层：视觉层。modifier（高度 / 宽度）作用于这一层，按压缩放也在这里。
        Box(
            modifier = modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(shape)
                .background(containerColor)
                .then(
                    if (borderColor != null) {
                        Modifier.border(width = 1.dp, color = borderColor, shape = shape)
                    } else {
                        Modifier
                    },
                )
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = textStyle,
                color = if (enabled) contentColor else contentColor.copy(alpha = DISABLED_ALPHA),
            )
        }
    }
}

/**
 * 平台选择弹窗：苹果设备 / 其他设备。
 *
 * 首次连接某设备与长按重选平台共用（文案由调用方给：重选时会说明「键盘布局会随之切换」）。
 */
@Composable
private fun PlatformDialog(
    title: String,
    message: String,
    onSelect: (DevicePlatform) -> Unit,
    onDismiss: () -> Unit,
) {
    MinimalDialog(onDismiss = onDismiss) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = PureWhite,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = PureWhite.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(20.dp))
        PlatformOption(
            text = stringResource(R.string.pairing_device_platform_apple),
            icon = {
                PlatformIcon(platform = DevicePlatform.APPLE, contentDescription = null)
            },
            onClick = { onSelect(DevicePlatform.APPLE) },
        )
        Spacer(modifier = Modifier.height(10.dp))
        PlatformOption(
            text = stringResource(R.string.pairing_device_platform_other),
            icon = {
                PlatformIcon(platform = DevicePlatform.OTHER, contentDescription = null)
            },
            onClick = { onSelect(DevicePlatform.OTHER) },
        )
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(
                text = stringResource(R.string.pairing_platform_dialog_cancel),
                color = PureWhite.copy(alpha = 0.6f),
            )
        }
    }
}

/**
 * 删除设备前的二次确认弹窗（Bug 3）。
 *
 * 为什么用二次确认而不是 Snackbar 撤销：`removeBond()` 之后 App 无法自己恢复配对
 * （重新 bond 必须由用户去对端蓝牙菜单再选一次「口袋键鼠」并确认），「撤销」撤不回来，
 * 给了反而误导。删除又是不可逆操作，因此在动作发生前拦住。
 */
@Composable
private fun DeleteConfirmDialog(
    deviceName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    MinimalDialog(onDismiss = onDismiss) {
        Text(
            text = stringResource(R.string.pairing_delete_confirm_title),
            style = MaterialTheme.typography.titleLarge,
            color = PureWhite,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.pairing_delete_confirm_message, deviceName),
            style = MaterialTheme.typography.bodyMedium,
            color = PureWhite.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.pairing_delete_confirm_cancel),
                    color = PureWhite.copy(alpha = 0.6f),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.pairing_delete_confirm_action),
                    color = DestructiveRed,
                )
            }
        }
    }
}

/** 配对页所有弹窗共用的卡片外壳：纯黑底、细白边、86% 宽、24dp 圆角。 */
@Composable
private fun MinimalDialog(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.86f),
            color = PureBlack,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(width = 1.dp, color = PureWhite.copy(alpha = 0.12f)),
        ) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp)) {
                content()
            }
        }
    }
}

/** 平台选择弹窗里的单个选项：平台图标 + 文案，整体水平居中。 */
@Composable
private fun PlatformOption(
    text: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MinimalGray)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(modifier = Modifier.width(10.dp))
            Text(text = text, style = MaterialTheme.typography.bodyLarge, color = PureWhite)
        }
    }
}
/**
 * 平台标识图标：苹果 logo / Windows logo（四格），纯代码矢量、白色填充。
 *
 * 路径数据与归一化见 [PlatformIconPaths] / [SvgPathNormalizer]：数据逐字符对齐上游
 * simple-icons（CC0）的 24×24 视口 path，解析前先归一化，避免「字符串拼接换行落在
 * 数字中间」造成的静默错位（Bug 1）。
 */
private object PlatformIcons {

    /** 苹果 logo（带叶子与咬口的剪影）。 */
    val Apple: Path by lazy {
        PathParser().parsePathString(SvgPathNormalizer.normalize(PlatformIconPaths.APPLE))
            .toPath(Path())
    }

    /** Windows logo（四格窗格）。 */
    val Windows: Path by lazy {
        PathParser().parsePathString(SvgPathNormalizer.normalize(PlatformIconPaths.WINDOWS))
            .toPath(Path())
    }

    fun of(platform: DevicePlatform): Path = when (platform) {
        DevicePlatform.APPLE -> Apple
        DevicePlatform.OTHER -> Windows
    }
}

/**
 * 设备平台图标（[PLATFORM_ICON_SIZE] 白色矢量）。
 *
 * @param contentDescription 读屏描述；传 null 时不给图标单独挂语义（旁边已有
 *     等价文字时避免读屏重复）。
 */
@Composable
private fun PlatformIcon(
    platform: DevicePlatform,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val path = remember(platform) { PlatformIcons.of(platform) }
    Canvas(
        modifier = modifier
            .size(PLATFORM_ICON_SIZE.dp)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
    ) {
        drawPlatformIconPath(path)
    }
}

/**
 * 把 24×24 视口内的平台徽标 [Path] 等比铺满当前画布（Bug 1 修复点）。
 *
 * 为什么必须显式给 [Offset.Zero]：`DrawScope.scale` 的 pivot 默认值是**画布中心**，
 * viewport 坐标 [0, 24] 绕中心放大后整条路径被平移到画布外，只剩一小块碎片可见
 * （真机上两个徽标都表现为「残缺/碎裂」）。以原点为 pivot 时 `[0,24]² → [0, size]²`
 * 正好铺满，与 SVG 的 preserveAspectRatio 行为一致。
 *
 * 抽成独立函数（而不是内联在 `PlatformIcon` 里）是为了让单测能在同一段生产代码上
 * 光栅化并断言覆盖率（见 `PlatformIconPathsTest`）。
 */
internal fun DrawScope.drawPlatformIconPath(
    path: Path,
    color: Color = PureWhite,
) {
    // SVG 路径是 24×24 视口，绘制时按图标实际尺寸等比缩放
    val factor = size.minDimension / PlatformIconPaths.VIEWPORT
    scale(scaleX = factor, scaleY = factor, pivot = Offset.Zero) {
        drawPath(path = path, color = color)
    }
}

/**
 * 五指挥势说明（配对页底部）。
 *
 * 手势识别本身在 `com.pocketkeyboard.app.gesture` 包里（`Modifier.pocketGestures`），
 * 这里只把阈值文案化地告诉用户，方便第一次使用时照着比划。
 */
@Composable
private fun GestureHint(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.gesture_hint_title),
            style = MaterialTheme.typography.labelLarge,
            color = PureWhite.copy(alpha = 0.55f),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.gesture_hint_body),
            style = MaterialTheme.typography.bodyMedium,
            color = PureWhite.copy(alpha = 0.4f),
        )
    }
}

/**
 * 设备行侧滑的几何判定（纯函数，便于单测）。
 *
 * 规则：只允许**向左**滑露出删除（右滑视为取消，夹回 0）；露出过半（含正好一半，
 * [shouldReveal]）才算进入「可点删除」态，松手后 spring 动画收起或保持露出。
 */
internal object DeviceRowSwipe {

    /** 把横向累计位移夹到合法的露出区间 `[-revealWidthPx, 0]`。 */
    fun clampOffset(totalX: Float, revealWidthPx: Float): Float =
        totalX.coerceIn(-revealWidthPx, 0f)

    /** 露出是否过半（过半 = 松手后保持露出、删除可点）。 */
    fun shouldReveal(offsetX: Float, revealWidthPx: Float): Boolean =
        offsetX <= -revealWidthPx / 2f
}
