package com.pocketkeyboard.app.ui.pairing

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketkeyboard.app.R
import com.pocketkeyboard.app.hid.HidController
import com.pocketkeyboard.app.ui.AppMode
import com.pocketkeyboard.app.ui.DevicePlatform
import com.pocketkeyboard.app.ui.MainViewModel
import com.pocketkeyboard.app.ui.PairedDevice
import com.pocketkeyboard.app.ui.theme.MinimalGray
import com.pocketkeyboard.app.ui.theme.PureBlack
import com.pocketkeyboard.app.ui.theme.PureWhite
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/** 配对码位数：6 位数字。 */
private const val PIN_LENGTH = 6

/** CONTROL 按钮按下时的缩放（苹果式按压手感）。 */
private const val PRESSED_SCALE = 0.97f

/** 禁用态文字透明度。 */
private const val DISABLED_ALPHA = 0.35f

/** 「请先配对」提示的自动消失时长。 */
private const val CONTROL_HINT_MILLIS = 2400L

/**
 * 配对页：展示本机蓝牙名称与 6 位数字配对码，管理已配对设备与控制目标。
 *
 * 页面不自己持有任何跨页状态：配对码 / 设备列表 / 当前控制目标 / 页面模式全部来自
 * [MainViewModel]（跨模块契约），本页只负责展示与写回；进入键盘页只通过
 * `viewModel.setMode(AppMode.KEYBOARD)` 切换，不写死页面跳转。
 *
 * @param hidController MainActivity 持有的唯一 HID 后端。选中设备时除写 ViewModel 外
 *    还要调 `hidController.setActiveDevice(address)`，把传输层的控制目标（registry）
 *    一起切过去，否则 UI 显示已切换、HID 报告却仍发给上一个设备。为 null 时（例如
 *    预览 / 单测）只写 ViewModel。
 */
@Composable
fun PairingScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = viewModel(),
    hidController: HidController? = null,
) {
    val context = LocalContext.current
    val platformStore = remember { DevicePlatformStore(context) }
    val scope = rememberCoroutineScope()

    val platforms by platformStore.platforms.collectAsStateWithLifecycle(initialValue = emptyMap())
    val pairedDevices by viewModel.pairedDevices.collectAsStateWithLifecycle()
    val activeDevice by viewModel.activeDevice.collectAsStateWithLifecycle()
    val pinCode by viewModel.pinCode.collectAsStateWithLifecycle()

    var localBluetooth by remember { mutableStateOf(LocalBluetoothState()) }
    var pendingDevice by remember { mutableStateOf<PairedDevice?>(null) }
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

    // 设备列表以 viewModel.pairedDevices 为唯一来源；系统 bonded 设备（localBluetooth）
    // 与 HID 后端上报的列表（bridge 写进 ViewModel）两条来源在这里合并去重，
    // 并用 DataStore 中持久化的平台类型校正（不覆盖其他模块已写入的平台信息）。
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

        // 已配对设备：点击切换当前控制目标
        DeviceList(
            devices = pairedDevices,
            activeDevice = activeDevice,
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
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        )

        Spacer(modifier = Modifier.height(16.dp))

        ControlButton(
            connected = activeDevice != null,
            onClick = {
                if (activeDevice != null) {
                    // 键盘页入口只通过 MainViewModel 切换 mode
                    viewModel.setMode(AppMode.KEYBOARD)
                } else {
                    showControlHint = true
                }
            },
        )

        ControlHint(visible = showControlHint)
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
            deviceName = device.name,
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
                pendingDevice = null
            },
            onDismiss = { pendingDevice = null },
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
            AppleButton(
                text = stringResource(R.string.pairing_make_discoverable),
                onClick = onMakeDiscoverable,
                enabled = localBluetooth.available && localBluetooth.permissionGranted,
                modifier = Modifier.height(36.dp),
                shape = RoundedCornerShape(percent = 50),
                containerColor = Color.Transparent,
                contentColor = PureWhite.copy(alpha = 0.85f),
                borderColor = PureWhite.copy(alpha = 0.35f),
                textStyle = MaterialTheme.typography.labelLarge,
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

/** 已配对设备列表：设备名 + 平台类型，点击切换当前控制目标。 */
@Composable
private fun DeviceList(
    devices: List<PairedDevice>,
    activeDevice: PairedDevice?,
    onDeviceClick: (PairedDevice) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.pairing_devices_title),
            style = MaterialTheme.typography.labelLarge,
            color = PureWhite.copy(alpha = 0.5f),
            letterSpacing = 2.sp,
        )
        Spacer(modifier = Modifier.height(10.dp))
        if (devices.isEmpty()) {
            Text(
                text = stringResource(R.string.pairing_devices_empty),
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
                onClick = { onDeviceClick(device) },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** 单个已配对设备行。 */
@Composable
private fun DeviceRow(
    device: PairedDevice,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MinimalGray)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        if (selected) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .border(
                        width = 1.dp,
                        color = PureWhite.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(percent = 50),
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.pairing_device_active),
                    style = MaterialTheme.typography.labelLarge,
                    color = PureWhite.copy(alpha = 0.75f),
                )
            }
        }
    }
}

/** 平台类型文案。 */
@Composable
private fun platformLabel(platform: DevicePlatform): String = when (platform) {
    DevicePlatform.APPLE -> stringResource(R.string.pairing_device_platform_apple)
    DevicePlatform.OTHER -> stringResource(R.string.pairing_device_platform_other)
}

/** CONTROL 大按钮：未连接设备时灰色不可点动，连接后黑底白字可点击进入键盘页。 */
@Composable
private fun ControlButton(
    connected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppleButton(
        text = stringResource(R.string.pairing_control),
        // 始终接收点击：未连接时点击用于给出「请先配对」提示
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(66.dp),
        shape = RoundedCornerShape(20.dp),
        containerColor = if (connected) PureBlack else MinimalGray,
        contentColor = if (connected) PureWhite else PureWhite.copy(alpha = DISABLED_ALPHA),
        borderColor = if (connected) PureWhite else null,
        textStyle = MaterialTheme.typography.titleLarge.copy(letterSpacing = 4.sp),
    )
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

/**
 * 苹果式按钮：无涟漪，按下缩放到 [PRESSED_SCALE]，松开以弹性弹簧回弹。
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
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = textStyle,
            color = if (enabled) contentColor else contentColor.copy(alpha = DISABLED_ALPHA),
        )
    }
}

/** 首次连接某设备时的平台选择弹窗：苹果设备 / 其他设备。 */
@Composable
private fun PlatformDialog(
    deviceName: String,
    onSelect: (DevicePlatform) -> Unit,
    onDismiss: () -> Unit,
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
                Text(
                    text = stringResource(R.string.pairing_platform_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = PureWhite,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.pairing_platform_dialog_message, deviceName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PureWhite.copy(alpha = 0.6f),
                )
                Spacer(modifier = Modifier.height(20.dp))
                PlatformOption(
                    text = stringResource(R.string.pairing_device_platform_apple),
                    onClick = { onSelect(DevicePlatform.APPLE) },
                )
                Spacer(modifier = Modifier.height(10.dp))
                PlatformOption(
                    text = stringResource(R.string.pairing_device_platform_other),
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
    }
}

/** 平台选择弹窗里的单个选项。 */
@Composable
private fun PlatformOption(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MinimalGray)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge, color = PureWhite)
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
