package com.pocketkeyboard.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pocketkeyboard.app.gesture.AppleSpring
import com.pocketkeyboard.app.gesture.GestureArbiter
import com.pocketkeyboard.app.gesture.GestureConstants
import com.pocketkeyboard.app.gesture.GestureFeedback
import com.pocketkeyboard.app.gesture.PocketGestureHandler
import com.pocketkeyboard.app.gesture.applePageTransitionSpec
import com.pocketkeyboard.app.gesture.cycleActiveDevice
import com.pocketkeyboard.app.gesture.pocketGestures
import com.pocketkeyboard.app.gesture.rememberGestureHudState
import com.pocketkeyboard.app.hid.HidController
import com.pocketkeyboard.app.keyboard.FusedControlScreen
import com.pocketkeyboard.app.keyboard.KeyboardScreen
import com.pocketkeyboard.app.trackpad.TrackpadScreen
import com.pocketkeyboard.app.ui.AppMode
import com.pocketkeyboard.app.ui.MainViewModel
import com.pocketkeyboard.app.ui.icon.ModeIcons
import com.pocketkeyboard.app.ui.pairing.PairingScreen
import com.pocketkeyboard.app.ui.theme.PocketKeyboardTheme
import kotlin.math.roundToInt

/**
 * 应用唯一 Activity：Compose 页面容器。
 *
 * 页面由 [MainViewModel] 的 mode 驱动切换：
 * PAIRING -> PairingScreen；KEYBOARD / NUMPAD / TRACKPAD -> 竖屏为融合控制页
 * （[FusedControlScreen]，上半触控板 + 下半 26 键 QWERTY）、横屏分别为
 * KeyboardScreen（87 键 TKL 全屏）/ TrackpadScreen（全屏触控板）。
 * 页面之间用 `AnimatedContent` + 苹果式 spring 曲线做推拉转场（见 gesture 包的
 * [applePageTransitionSpec]），**不直接操作 NavHost**。
 *
 * ## dock（底部 ModeSwitcher）可见性规则
 *
 * - 配对页常显（配对流程需要三栏导航）；
 * - 键盘页 / 触控板页默认隐藏，把空间腾给键盘 / 触控板；
 * - 单指从屏幕左缘或右缘向内滑动超过 24dp（见 [edgeSwipeToShowDock]）以苹果式
 *   spring 动画临时唤出 dock；
 * - 一旦切换页面模式（点 dock 项或五指挥势切模式）立即再次自动隐藏。
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

/** 应用根 Composable：权限申请 + 五指挥势层 + 页面容器 + dock 覆盖层。 */
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

    // 横竖屏：manifest 声明了 configChanges 含 orientation，旋转不会重建 Activity，
    // 这里读 LocalConfiguration 直接切换布局（横屏 = 全屏 87 键键盘 / 全屏触控板）
    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // ---------------------------------------------------------------- dock 可见性
    //
    // 规则（纯函数部分见 [dockVisibleFor]，单测覆盖）：
    // - 配对页常显；
    // - 键盘 / 触控板页默认隐藏，仅「单指边缘内滑」临时唤出；
    // - 切换页面模式后立刻复位 → dock 再次自动隐藏（产品需求原文）。
    var dockEdgeActivated by remember { mutableStateOf(false) }
    LaunchedEffect(mode) {
        // mode 一变（点 dock 项 / 五指挥势切模式 / 五指横滑不改 mode 不触发）就收回激活态
        dockEdgeActivated = false
    }
    val dockVisible = dockVisibleFor(mode, dockEdgeActivated)

    // 系统返回手势抢占问题：手势导航下，从屏幕左右边缘开始的触摸会被 SystemUI 的
    // 「边缘返回手势」先截走（真机实测：小米从左缘内滑直接触发系统返回、App 收不到事件），
    // dock 激活层就永远等不到那次按下。两层对策：
    // ① View.setSystemGestureExclusionRects 把左右 [EdgeSwipeZoneWidth] 边缘带声明为
    //    系统手势排除区（原生 Android 生效，见 EdgeSwipeSystemGestureExclusion）；
    // ② BackHandler：dock 隐藏期间把「返回」也接到 dock 激活上——MIUI 这类不理会排除区
    //    的系统上，用户从左缘内滑触发的是系统返回，这里拦下来唤出 dock 而不是退出 App，
    //    于是「边缘内滑 → dock 滑入」的产品语义在两类系统上都能兑现；dock 可见时恢复
    //    系统返回语义（再触发一次返回才退出 App）。小键盘 sheet 打开时它自己的
    //    BackHandler 后注册、优先生效，先收 sheet 再谈 dock。
    BackHandler(enabled = !dockVisible) {
        dockEdgeActivated = true
    }
    EdgeSwipeSystemGestureExclusion(enabled = !dockVisible)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // dock 隐藏时让出系统手势条：键盘最底行 / 触控区也不会被导航栏盖住
                .navigationBarsPadding()
                // 五指挥势层：挂在页面容器最底层，键盘层 / 触控板层作为子节点与之共存
                .pocketGestures(handler = gestureHandler, arbiter = arbiter)
                // 侧滑激活 dock：只在 dock 隐藏时挂载；配对页 dock 常显，无需激活
                .edgeSwipeToShowDock(
                    enabled = !dockVisible,
                    onActivate = { dockEdgeActivated = true },
                ),
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

                    // 竖屏：两种 mode 都是融合布局（触控板 + 26 键），mode 状态照常切换，
                    // 只是视觉不再变化——五指收缩 / 张开在竖屏已无页面可切
                    AppMode.KEYBOARD, AppMode.NUMPAD ->
                        if (landscape) {
                            KeyboardScreen(
                                modifier = Modifier.fillMaxSize(),
                                viewModel = viewModel,
                                transport = hidController.transport,
                            )
                        } else {
                            FusedControlScreen(
                                modifier = Modifier.fillMaxSize(),
                                viewModel = viewModel,
                                arbiter = arbiter,
                                transport = hidController.transport,
                            )
                        }

                    // 横屏：全屏触控板（TrackpadScreen 自带完整手势层与按键模拟区）
                    AppMode.TRACKPAD ->
                        if (landscape) {
                            TrackpadScreen(
                                modifier = Modifier.fillMaxSize(),
                                viewModel = viewModel,
                                arbiter = arbiter,
                                transport = hidController.transport,
                            )
                        } else {
                            FusedControlScreen(
                                modifier = Modifier.fillMaxSize(),
                                viewModel = viewModel,
                                arbiter = arbiter,
                                transport = hidController.transport,
                            )
                        }
                }
            }

            // HUD 与设备切换覆盖层：画在最上层，但不加 pointerInput，对触摸完全透明
            GestureFeedback(state = hudState)
        }

        // dock 覆盖层：贴在内容之上（不挤占页面空间），以苹果式 spring 从底部滑入 / 滑出。
        // 滑出由 mode 变化触发（LaunchedEffect 复位 dockEdgeActivated）。
        AnimatedVisibility(
            visible = dockVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(animationSpec = AppleSpring.intOffset) { full -> full } +
                fadeIn(animationSpec = AppleSpring.float),
            exit = slideOutVertically(animationSpec = AppleSpring.intOffset) { full -> full } +
                fadeOut(animationSpec = AppleSpring.float),
        ) {
            ModeSwitcher(
                currentMode = mode,
                onModeSelected = viewModel::setMode,
            )
        }
    }
}

/**
 * dock 可见性策略（纯函数，便于单测）。
 *
 * - 配对页：常显（配对流程需要三栏导航）；
 * - 键盘 / 触控板页：默认隐藏，仅当用户完成过一次「单指边缘内滑」激活时可见。
 *   「切换页面模式后再次自动隐藏」由调用方的 `LaunchedEffect(mode)` 复位激活态实现，
 *   因此这里只看当前 mode 与激活态，不持有历史。
 */
internal fun dockVisibleFor(mode: AppMode, edgeActivated: Boolean): Boolean =
    when (mode) {
        AppMode.PAIRING -> true
        AppMode.KEYBOARD, AppMode.NUMPAD, AppMode.TRACKPAD -> edgeActivated
    }

/**
 * 给页面容器挂上「单指边缘内滑唤出 dock」识别层。
 *
 * ## 与五指挥势层 / 按键层的事件仲裁（改代码前必读）
 *
 * 页面结构上，本层与五指挥势层（`Modifier.pocketGestures`）挂在**同一个** Box 上，
 * 键盘按键层 / 触控板层是它们的子节点。Compose 的一次 `PointerEvent` 按三个 pass
 * 沿 hit-test 路径下发：
 *
 * ```
 * Initial：根 → 叶   父节点先看到，父可以在这里抢先 consume
 * Main   ：叶 → 根   子节点先看到，子消费掉的事件父节点仍会收到但 isConsumed == true
 * Final  ：根 → 叶   父节点兜底
 * ```
 *
 * 本层与五指挥势层都读 **Initial pass**（父先于子），并按下面的规则分工：
 *
 * 1. **边缘带落下即接管**：手指落在左右边缘 [EdgeSwipeZoneWidth] 内时，本层立刻
 *    `consume()` 该 down。按键层（`Modifier.pocketKeyGestures`）的入口是
 *    `awaitFirstDown(requireUnconsumed = true)`，被消费的 down 它再也看不到——
 *    因此这次边缘滑动**不会被任何按键消费掉**，不会顺带打出字符；
 * 2. **向内滑过 [EdgeSwipeTriggerDistance] 才激活**：未达阈值时本次触摸只是「边缘带内的
 *    一次死点击」（该窄带内的按下不命中任何键，是激活带的占位代价，与系统返回手势的
 *    边缘抢占同思路）；达到阈值即调 [onActivate]，dock 以苹果式 spring 滑入；
 * 3. **≥5 指针立即放行**：一旦按下指针数达到 [GestureConstants.REQUIRED_FINGER_COUNT]，
 *    本层停止消费、直接结束本轮 `awaitEachGesture`。五指挥势层同样读 Initial pass 且
 *    **不检查 isConsumed**，所以它仍能完整拿到这 5 根手指做收缩 / 张开 / 横滑识别——
 *    单指边缘滑动因此也不会被五指挥势层吃掉；
 * 4. **不参与 GestureArbiter 仲裁**：本层只对「单指 + 落在边缘带」这一种触摸感兴趣，
 *    其余全部原样放行，1–4 指的触控板手势 / 单指打字不受任何影响；
 * 5. **只在 dock 隐藏时挂载**（[enabled] = false 时整个手势层不挂载）：dock 可见或
 *    配对页常显时，边缘带恢复为普通可点击区域。
 *
 * ## 与系统返回手势的关系（真机实测）
 *
 * 手势导航的系统「边缘返回手势」和本层抢同一块区域。原生 Android 上
 * [EdgeSwipeSystemGestureExclusion] 的排除区能让本层完整拿到整个激活带；MIUI 这类
 * 不理会排除区的系统上，最外侧约 18dp 仍会被系统截走（直接触发系统返回）。因此
 * `PocketKeyboardApp` 另有一层 [BackHandler]：dock 隐藏期间把系统返回也接到 dock
 * 激活上——从最左 / 右缘内滑触发系统返回时，App 不退到后台而是唤出 dock，
 * 「边缘内滑 → dock 滑入」的产品语义不受影响。
 *
 * @param enabled false 时整个手势层不挂载
 * @param edgeZoneWidth 边缘激活带宽度（手指落下位置到屏幕边缘的最大距离）
 * @param triggerDistance 触发距离（落于激活带的手指向内滑动超过该值即激活）
 * @param onActivate 激活回调（通常是把「dock 已激活」状态置 true）
 */
@Composable
private fun Modifier.edgeSwipeToShowDock(
    enabled: Boolean,
    edgeZoneWidth: Dp = EdgeSwipeZoneWidth,
    triggerDistance: Dp = EdgeSwipeTriggerDistance,
    onActivate: () -> Unit,
): Modifier {
    if (!enabled) return this
    val density = LocalDensity.current
    val zonePx = with(density) { edgeZoneWidth.toPx() }
    val triggerPx = with(density) { triggerDistance.toPx() }
    return this.then(
        Modifier.pointerInput(zonePx, triggerPx) {
            detectEdgeSwipeToShowDock(
                zonePx = zonePx,
                triggerPx = triggerPx,
                onActivate = onActivate,
            )
        },
    )
}

/** 边缘激活带宽度：手指必须落在屏幕左右边缘该宽度内才会被 dock 激活层接管。 */
private val EdgeSwipeZoneWidth = 24.dp

/** 触发距离：边缘带内落下的手指向内滑动超过该距离即激活 dock。 */
private val EdgeSwipeTriggerDistance = 24.dp

/**
 * 系统手势排除区：dock 隐藏期间把左右 [EdgeSwipeZoneWidth] 边缘带交还给 App。
 *
 * ## 为什么需要它（真机实测记录）
 *
 * 小米手机（MIUI、手势导航）上，从左缘 / 右缘开始的触摸会被 SystemUI 的边缘返回手势
 * 先行截走：实测从左缘内滑 395px 直接触发系统返回（App 退到后台）、内滑 115px 时
 * App 既收不到按下也收不到移动——dock 激活层根本等不到事件。这不是 Compose 层能
 * 解决的问题，必须用平台 API `View.setSystemGestureExclusionRects`（API 29+）声明
 * 排除区，让系统把该带内的触摸让给 App。
 *
 * 权衡：dock 隐藏（键盘 / 触控板页）时系统返回手势在左右 24dp 带内失效——这两个页面
 * 本来就没有「返回」语义（页面切换走 dock 与五指挥势），而边缘内滑是产品指定的 dock
 * 激活方式，因此让位于产品交互；dock 可见（含配对页常显）时立即清空排除区，
 * 系统返回手势完全恢复正常。minSdk 28 上该 API 不存在，直接跳过（那些版本默认是
 * 三键导航或没有边缘返回抢占问题）。
 */
@Composable
private fun EdgeSwipeSystemGestureExclusion(enabled: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    val view = LocalView.current
    val bandPx = with(LocalDensity.current) { EdgeSwipeZoneWidth.roundToPx() }

    fun applyExclusion() {
        val width = view.width
        val height = view.height
        view.setSystemGestureExclusionRects(
            if (enabled && width > 0 && height > 0) {
                listOf(
                    Rect(0, 0, bandPx, height),
                    Rect(width - bandPx, 0, width, height),
                )
            } else {
                emptyList()
            },
        )
    }

    DisposableEffect(enabled, bandPx) {
        applyExclusion()
        // 旋转 / 首次布局后 view 尺寸才确定，监听布局变化补一次
        val layoutListener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View?,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int,
            ) {
                applyExclusion()
            }
        }
        view.addOnLayoutChangeListener(layoutListener)
        onDispose {
            view.removeOnLayoutChangeListener(layoutListener)
            view.setSystemGestureExclusionRects(emptyList())
        }
    }
}

/**
 * 边缘内滑识别的几何判定（纯函数，便于单测）。
 */
internal object EdgeSwipeGeometry {

    /** 落下位置相对哪条边缘。 */
    enum class Side { LEFT, RIGHT, NONE }

    /** 落下位置 [downX]（px，相对容器左边）是否落在左右边缘激活带内。 */
    fun sideOf(downX: Float, widthPx: Float, zonePx: Float): Side = when {
        downX <= zonePx -> Side.LEFT
        downX >= widthPx - zonePx -> Side.RIGHT
        else -> Side.NONE
    }

    /** 手指从落点向内滑动了多少 px（负数表示向外滑，永远达不到触发阈值）。 */
    fun inwardProgress(side: Side, downX: Float, currentX: Float): Float = when (side) {
        Side.LEFT -> currentX - downX
        Side.RIGHT -> downX - currentX
        Side.NONE -> 0f
    }
}

/**
 * 边缘内滑识别主循环。
 *
 * ```
 * awaitEachGesture {                                  // 每轮都从「所有手指抬起」的干净状态开始
 *   down = awaitFirstDown(Initial)                    // 父节点先于按键 / 触控板看到按下
 *   side = EdgeSwipeGeometry.sideOf(down)             // 不在边缘带 → 不消费、放行
 *   down.consume()                                    // 在边缘带 → 按键层再也看不到这根手指
 *   loop {
 *     event = awaitPointerEvent(Initial)
 *     pressed 为空 → 手指全部抬起，结束
 *     pressed ≥ 5 → 放行给五指挥势层（它不检查 isConsumed），结束
 *     progress ≥ triggerPx → onActivate()（只触发一次）
 *     consume 全部 change                              // 手势期间不让按键 / 触控板层抢走
 *   }
 * }
 * ```
 */
private suspend fun PointerInputScope.detectEdgeSwipeToShowDock(
    zonePx: Float,
    triggerPx: Float,
    onActivate: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val side = EdgeSwipeGeometry.sideOf(down.position.x, size.width.toFloat(), zonePx)
        if (side == EdgeSwipeGeometry.Side.NONE) {
            // 不在边缘带：什么都不消费，事件留给按键层 / 触控板层 / 五指挥势层
            return@awaitEachGesture
        }
        // 在边缘带：立刻消费 down。按键层入口 requireUnconsumed = true，从此看不到这根手指，
        // 因此边缘滑动不可能被按键消费掉（详见 [edgeSwipeToShowDock] 的仲裁注释）
        down.consume()

        var activated = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (pressed.size >= GestureConstants.REQUIRED_FINGER_COUNT) {
                // 凑满 5 指：本次触摸归五指挥势层，本层停手（不再消费、不激活）
                break
            }
            val progress = pressed.maxOf {
                EdgeSwipeGeometry.inwardProgress(side, down.position.x, it.position.x)
            }
            if (!activated && progress >= triggerPx) {
                activated = true
                onActivate()
            }
            event.changes.forEach { it.consume() }
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
