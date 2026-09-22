#!/usr/bin/env bash
# 口袋键鼠（PocketKeyboard）演示截图脚本
#
# 用途：每次 App 更新后重新截取 docs/screenshots/ 下的演示截图，
# 保证 README 里的截图与最新 UI 一致。
#
# 前置条件：
#   1. 一台已开启 USB 调试的安卓手机通过 USB 连接（在手机上确认「允许调试」）
#   2. 本机已安装 Android SDK platform-tools（adb）
#   3. 已构建并安装最新 APK：./gradlew assembleRelease && adb install -r app/build/outputs/apk/release/app-release.apk
#   4. 手机已解锁（本脚本只负责唤醒，滑动锁需提前解除）
#   5. 蓝牙权限已授予。重装 APK 后 MIUI 会依次弹「位置 / 通知 / 蓝牙」权限框，
#      需要先手动点掉（脚本里的 pm grant 对部分 MIUI 版本不生效）；
#      脚本已尽量用 pm grant 预授，仍弹框时请按提示手动允许。
#
# 用法：
#   tools/capture-screenshots.sh [设备序列号]
# 不带参数时自动选择唯一连接的设备。
#
# 坐标按 1080x2400（密度 2.75）的小米手机校准；换机型请调整下面的常量。
set -euo pipefail

cd "$(dirname "$0")/.."
OUT="docs/screenshots"
mkdir -p "$OUT"

# 设备选择：参数指定，或唯一连接设备
if [ $# -ge 1 ]; then
  DEV="$1"
elif [ "$(adb devices | grep -wc device)" = "1" ]; then
  DEV="$(adb devices | grep -w device | awk '{print $1}')"
else
  echo "用法: $0 <设备序列号>  （adb devices 查看）" >&2
  adb devices >&2
  exit 1
fi
echo "使用设备: $DEV"

PKG="com.pocketkeyboard.app"
ACT="$PKG/.MainActivity"

echo "==> 唤醒并启动 App"
adb -s "$DEV" shell input keyevent 224 >/dev/null
# 先固定到竖屏：设备可能停留在上次的横屏状态，dock 坐标会整体失效
adb -s "$DEV" shell settings put system accelerometer_rotation 0
adb -s "$DEV" shell settings put system user_rotation 0
sleep 1
adb -s "$DEV" shell am force-stop "$PKG"
# 预授运行时权限（重装后 MIUI 可能仍会弹框，需手动点「始终允许」）
for PERM in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION BLUETOOTH_CONNECT \
            BLUETOOTH_SCAN BLUETOOTH_ADVERTISE POST_NOTIFICATIONS; do
  adb -s "$DEV" shell pm grant "$PKG" "android.permission.$PERM" >/dev/null 2>&1 || true
done
adb -s "$DEV" shell am start -n "$ACT" >/dev/null
sleep 3
# 等待广播名改写生效（App 启动后把蓝牙名设为 PocketKeyboard-<品牌>）：
# force-stop 后首次启动可能要先走注册自愈（约 20s）才改名，截早了会拍到旧系统名。
echo "==> 等待广播名 PocketKeyboard-* 生效"
for _ in $(seq 1 40); do
  NAME="$(adb -s "$DEV" shell settings get secure bluetooth_name | tr -d '\r')"
  case "$NAME" in PocketKeyboard-*) break ;; esac
  sleep 1
done
sleep 1

# 底部 dock 三项的中心坐标（1080x2400 竖屏）：配对 / 键盘 / 触控板
NAV_PAIRING_X=180
NAV_KEYBOARD_X=540
NAV_TRACKPAD_X=900
NAV_Y=2317
# 横屏（2400x1080）下 dock 三项的中心坐标（横屏底栏更靠上、三项均分全宽）
LAND_NAV_PAIRING_X=393
LAND_NAV_KEYBOARD_X=1200
LAND_NAV_TRACKPAD_X=2007
LAND_NAV_Y=1014
# 「123」小键盘开关中心（竖屏融合布局右上角，已避开状态栏）。
# 坐标经 uiautomator 标定：可点击节点 bounds [871,113][1047,198]，中心 (959,155)。
NUMPAD_BTN_X=959
NUMPAD_BTN_Y=155
# 边缘内滑起点：x=5 在最左缘。MIUI 等手势导航系统会把它当成系统返回手势，
# 由 App 的 BackHandler 接到「唤出 dock」上；原生 Android 上由边缘激活层直接识别。
EDGE_SWIPE_X=5

shot() { # shot <设备上的文件名> <本地文件名>
  adb -s "$DEV" shell screencap -p "/sdcard/$1"
  adb -s "$DEV" pull "/sdcard/$1" "$OUT/$2" >/dev/null
  adb -s "$DEV" shell rm -f "/sdcard/$1"
  echo "  已保存 $OUT/$2"
}

# 唤出 dock：dock 在键盘 / 触控板页默认自动隐藏，切换模式前必须先边缘内滑唤出。
# 横屏时滑动起点纵坐标取半高（540），竖屏取半高（1200）。
# 手势：从最左缘（x=5）向内滑到 x=300（远超 24dp≈66px 的触发阈值）。
show_dock() { # show_dock <滑动起点纵坐标>
  local Y="$1"
  adb -s "$DEV" shell input swipe "$EDGE_SWIPE_X" "$Y" 300 "$Y" 250
  sleep 1
}

echo "==> 1/4 配对页（dock 常显）"
shot cap_pairing.png 01_pairing.png

echo "==> 2/4 键盘页（竖屏融合布局：上触控板 + 系统输入法唤起区 + 可锁定修饰键排）"
adb -s "$DEV" shell input tap "$NAV_KEYBOARD_X" "$NAV_Y"
sleep 2
shot cap_keyboard.png 02_keyboard.png

echo "==> 3/4 融合页 + 系统输入法弹出（点输入区唤起手机输入法）"
# 输入区提示文字位置（1080x2400 竖屏），点击后弹出系统输入法
adb -s "$DEV" shell input tap 540 2100
sleep 3
shot cap_trackpad.png 03_trackpad.png
adb -s "$DEV" shell input keyevent 4   # 收起系统键盘
sleep 2

echo "==> 4/4 数字小键盘（融合页右上角 123 sheet）"
adb -s "$DEV" shell input tap "$NUMPAD_BTN_X" "$NUMPAD_BTN_Y"
sleep 2
shot cap_numpad.png 04_numpad.png

echo "==> 收起小键盘，恢复自动旋转"
adb -s "$DEV" shell input tap "$NUMPAD_BTN_X" "$NUMPAD_BTN_Y"
adb -s "$DEV" shell settings put system accelerometer_rotation 1
sleep 1

echo "完成。若你的手机分辨率不是 1080x2400，请调整脚本里的坐标常量。"
