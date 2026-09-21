package com.pocketkeyboard.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketkeyboard.app.gesture.GestureArbiter
import com.pocketkeyboard.app.gesture.GestureFeedback
import com.pocketkeyboard.app.gesture.PocketGestureHandler
import com.pocketkeyboard.app.gesture.applePageTransitionSpec
import com.pocketkeyboard.app.gesture.cycleActiveDevice
import com.pocketkeyboard.app.gesture.pocketGestures
import com.pocketkeyboard.app.gesture.rememberGestureHudState
import com.pocketkeyboard.app.hid.HidController
import com.pocketkeyboard.app.keyboard.KeyboardScreen
import com.pocketkeyboard.app.trackpad.TrackpadScreen
import com.pocketkeyboard.app.ui.AppMode
import com.pocketkeyboard.app.ui.MainViewModel
import com.pocketkeyboard.app.ui.icon.ModeIcons
import com.pocketkeyboard.app.ui.pairing.PairingScreen
import com.pocketkeyboard.app.ui.theme.PocketKeyboardTheme

/**
 * 应用唯一 Activity：Compose 页面容器。
 *
 * 页面由 [MainViewModel] 的 mode 驱动切换：
 * PAIRING -> PairingScreen，KEYBOARD / NUMPAD -> KeyboardScreen，TRACKPAD -> TrackpadScreen。
 * 页面之间用 `AnimatedContent` + 苹果式 spring 曲线做推拉转场（见 gesture 包的
 * [applePageTransitionSpec]），**不直接操作 NavHost**。
 *
 * 本 Activity 还持有**唯一**的 [HidController]（HID 后端入口）：随根组合启停，
 * `controller.transport` 注入键盘页 / 触控板页，五指横滑与配对页点击设备时调用
 * `controller.setActiveDevice` 同步传输层控制目标。页面级各自持有 controller 会导致
 * 切页反复 `registerApp` / `unregisterApp`（对端掉线、注册被拒后不自愈），故提升到这里。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // edge-to-edge：透明黑深色状态栏 / 导航栏，纯黑背景延伸至屏幕边缘
        enableEdgeToEdge()
        setContent {
            PocketKeyboardTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PocketKeyboardApp()
                }
            }
        }
    }
}

/** 应用根 Composable：权限申请 + 五指挥势层 + 页面容器。 */
@Composable
private fun PocketKeyboardApp(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current

    // 蓝牙相关运行时权限是否已授予：HID 注册（registerApp）需要 BLUETOOTH_CONNECT，
    // 在权限弹窗返回前调用会 SecurityException，因此必须等授予后再启动。
    var bluetoothPermissionsGranted by remember {
        mutableStateOf(hasBluetoothPermissions(context))
    }
    // 进入 App 时弹运行时权限申请；结果里蓝牙相关权限全部 granted 才解锁 HID 启动
    RequestRuntimePermissionsOnLaunch(onResult = { result ->
        bluetoothPermissionsGranted = result.entries
            .filter { it.key != Manifest.permission.POST_NOTIFICATIONS }
            .all { it.value } && hasBluetoothPermissions(context)
    })

    val mode by viewModel.mode.collectAsStateWithLifecycle()

    // 手势视觉反馈（HUD 文字提示 + 设备切换覆盖式转场）
    val hudState = rememberGestureHudState()

    // 手势仲裁器：同一个实例同时交给最底层的五指挥势层和触控板层，
    // 否则「60ms 内凑满 5 指归五指层」这条规则无从生效
    val arbiter = remember { GestureArbiter() }

    // HID 后端唯一实例：整个 App（而非单个页面）生命周期内只注册一次 HID 外设。
    // 页面切换（AnimatedContent）不再反复 registerApp / unregisterApp，对端不会掉线；
    // 键盘页 / 触控板页只消费 controller.transport，多设备切换走 controller.setActiveDevice。
    val hidController = remember { HidController(context.applicationContext, viewModel) }

    // 权限授予后再 start()：未授予时 HidDeviceTransport 会上报不可用原因，
    // 配对页据此提示用户；授予后（含从设置页返回的复查）这里负责补启动。
    LaunchedEffect(bluetoothPermissionsGranted) {
        if (bluetoothPermissionsGranted) hidController.start()
    }
    // 每次回到前台都复查权限并补一次 start()：
    // 1) 用户在系统设置里补授权后返回（权限状态在 ON_START 才刷新）；
    // 2) 系统 HidDeviceService 的 registerApp 要求 App 处于前台，锁屏/后台期间
    //    注册被拒（REGISTRATION_REJECTED）后只有回到前台重试才能恢复。
    // start() 幂等（失败路径已复位 started 标志），重复调用无副作用。
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        bluetoothPermissionsGranted = hasBluetoothPermissions(context)
        if (bluetoothPermissionsGranted) hidController.start()
    }
    DisposableEffect(hidController) {
        onDispose { hidController.stop() }
    }

    // HUD 文案全部来自 strings.xml（组合期解析，手势回调里直接用）
    val trackpadHud = stringResource(R.string.hud_trackpad_mode)
    val keyboardHud = stringResource(R.string.hud_keyboard_mode)
    val deviceSwitchedHud = stringResource(R.string.hud_device_switched)
    val noDeviceHud = stringResource(R.string.hud_no_paired_device)

    val gestureHandler = remember(
        viewModel,
        hudState,
        hidController,
        trackpadHud,
        keyboardHud,
        deviceSwitchedHud,
        noDeviceHud,
    ) {
        PocketGestureHandler(
            // 五指收缩 → 触控板模式
            onPinch = {
                viewModel.setMode(AppMode.TRACKPAD)
                hudState.show(trackpadHud)
            },
            // 五指张开 → 键盘模式
            onSpread = {
                viewModel.setMode(AppMode.KEYBOARD)
                hudState.show(keyboardHud)
            },
            // 五指整体横滑 → 在已配对设备间循环切换 activeDevice
            onSwipe = { direction ->
                // 直接读 StateFlow 的当前值，避免 remember 住一份会过期的快照
                val devices = viewModel.pairedDevices.value
                if (devices.isEmpty()) {
                    hudState.show(noDeviceHud)
                    return@PocketGestureHandler
                }
                val next = cycleActiveDevice(devices, viewModel.activeDevice.value, direction)
                if (next != null) {
                    // 先同步 HID 后端的控制目标（registry）：只写 ViewModel 的话
                    // sendToActive 仍把报告发给上一个 activeAddress，键鼠事件打错设备。
                    // registry 认识该地址时 setActiveDevice 会经 bridge 回写一次
                    // ViewModel（平台是探测值），因此之后再用 pairedDevices 里的
                    // 条目（平台以 DataStore 为准）覆盖，保证最终展示与布局正确。
                    hidController.setActiveDevice(next.address)
                    viewModel.setActiveDevice(next)
                    // 覆盖式转场 + HUD 文案（「已切换至 xxx」）
                    hudState.showDeviceSwitch(deviceSwitchedHud.format(next.name))
                }
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            ModeSwitcher(
                currentMode = mode,
                onModeSelected = viewModel::setMode,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // 五指挥势层：挂在页面容器最底层，键盘层 / 触控板层作为子节点与之共存
                .pocketGestures(handler = gestureHandler, arbiter = arbiter),
        ) {
            AnimatedContent(
                targetState = mode,
                modifier = Modifier.fillMaxSize(),
                // 苹果式 spring 推拉转场（stiffness = MediumLow）
                transitionSpec = applePageTransitionSpec(),
                label = "page",
            ) { currentMode ->
                when (currentMode) {
                    AppMode.PAIRING -> PairingScreen(
                        modifier = Modifier.fillMaxSize(),
                        viewModel = viewModel,
                        hidController = hidController,
                    )
                    AppMode.KEYBOARD, AppMode.NUMPAD -> KeyboardScreen(
                        modifier = Modifier.fillMaxSize(),
                        viewModel = viewModel,
                        transport = hidController.transport,
                    )
                    AppMode.TRACKPAD -> TrackpadScreen(
                        modifier = Modifier.fillMaxSize(),
                        viewModel = viewModel,
                        arbiter = arbiter,
                        transport = hidController.transport,
                    )
                }
            }

            // HUD 与设备切换覆盖层：画在最上层，但不加 pointerInput，对触摸完全透明
            GestureFeedback(state = hudState)
        }
    }
}

/** 进入 App 时请求运行时权限（Android 12+ 按规范拆分蓝牙权限组）。 */
@Composable
private fun RequestRuntimePermissionsOnLaunch(onResult: (Map<String, Boolean>) -> Unit) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = onResult,
    )

    LaunchedEffect(Unit) {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            // Android 11 及以下扫描 BLE 外设需要精确定位
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (permissions.isNotEmpty()) {
            launcher.launch(permissions.toTypedArray())
        }
    }
}

/** HID 注册所必需的蓝牙相关权限（POST_NOTIFICATIONS 不阻塞蓝牙功能，不计入）。 */
private fun hasBluetoothPermissions(context: Context): Boolean {
    val required = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    return required.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}

/** 底部模式切换栏：配对 / 键盘 / 触控板。 */
@Composable
private fun ModeSwitcher(
    currentMode: AppMode,
    onModeSelected: (AppMode) -> Unit,
) {
    data class ModeItem(val mode: AppMode, val labelRes: Int, val icon: ImageVector)

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        val items = listOf(
            ModeItem(AppMode.PAIRING, R.string.mode_pairing, ModeIcons.Pairing),
            ModeItem(AppMode.KEYBOARD, R.string.mode_keyboard, ModeIcons.Keyboard),
            ModeItem(AppMode.TRACKPAD, R.string.mode_trackpad, ModeIcons.Trackpad),
        )
        items.forEach { item ->
            // 穷举 AppMode 的四个值、不加 else：以后新增模式会在这里编译报错，
            // 而不是静默出现「底栏不高亮」的失联状态
            val selected = when (item.mode) {
                AppMode.PAIRING -> currentMode == AppMode.PAIRING
                AppMode.KEYBOARD -> currentMode == AppMode.KEYBOARD || currentMode == AppMode.NUMPAD
                AppMode.TRACKPAD -> currentMode == AppMode.TRACKPAD
                AppMode.NUMPAD -> currentMode == AppMode.KEYBOARD || currentMode == AppMode.NUMPAD
            }
            NavigationBarItem(
                selected = selected,
                onClick = { onModeSelected(item.mode) },
                icon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = stringResource(item.labelRes),
                    )
                },
                label = { Text(text = stringResource(item.labelRes)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
