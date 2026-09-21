#!/usr/bin/env bash
# 口袋键鼠（PocketKeyboard）演示截图脚本
#
# 用途：每次 App 更新后重新截取 docs/screenshots/ 下的演示截图，
# 保证 README 里的截图与最新 UI 一致。
#
# 前置条件：
#   1. 一台已开启 USB 调试的安卓手机通过 USB 连接（在手机上确认「允许调试」）
#   2. 本机已安装 Android SDK platform-tools（adb）
#   3. 已构建并安装最新 APK：./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
#   4. 手机已解锁（本脚本只负责唤醒，滑动锁需提前解除）
#
# 用法：
#   tools/capture-screenshots.sh [设备序列号]
# 不带参数时自动选择唯一连接的设备。
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
adb -s "$DEV" shell am force-stop "$PKG"
adb -s "$DEV" shell am start -n "$ACT" >/dev/null
sleep 2

# 底部导航坐标（1080x2400 屏幕）：配对 / 键盘 / 触控板
NAV_PAIRING_X=180
NAV_KEYBOARD_X=540
NAV_TRACKPAD_X=900
NAV_Y=2250
# 触控板右上角「123」小键盘切换按钮中心（1080x2400 屏幕）
NUMPAD_BTN_X=959
NUMPAD_BTN_Y=155

shot() { # shot <设备上的文件名> <本地文件名>
  adb -s "$DEV" shell screencap -p "/sdcard/$1"
  adb -s "$DEV" pull "/sdcard/$1" "$OUT/$2" >/dev/null
  adb -s "$DEV" shell rm -f "/sdcard/$1"
  echo "  已保存 $OUT/$2"
}

echo "==> 1/4 配对页"
adb -s "$DEV" shell input tap "$NAV_PAIRING_X" "$NAV_Y"
sleep 2
shot cap_pairing.png 01_pairing.png

echo "==> 2/4 键盘页（87 键布局）"
adb -s "$DEV" shell input tap "$NAV_KEYBOARD_X" "$NAV_Y"
sleep 2
shot cap_keyboard.png 02_keyboard.png

echo "==> 3/4 触控板页"
adb -s "$DEV" shell input tap "$NAV_TRACKPAD_X" "$NAV_Y"
sleep 2
shot cap_trackpad.png 03_trackpad.png

echo "==> 4/4 数字小键盘"
adb -s "$DEV" shell input tap "$NUMPAD_BTN_X" "$NUMPAD_BTN_Y"
sleep 2
shot cap_numpad.png 04_numpad.png

echo "==> 收起小键盘，回到触控板页"
adb -s "$DEV" shell input tap "$NUMPAD_BTN_X" "$NUMPAD_BTN_Y"
sleep 1

echo "完成。若你的手机分辨率不是 1080x2400，请调整脚本顶部的坐标常量。"
