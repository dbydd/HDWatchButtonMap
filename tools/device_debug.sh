#!/usr/bin/env bash
# 实机调试第一步：检测手表、诊断 HID device profile、装 APK、授权、推配置、拉起 app。
# 用法: tools/device_debug.sh [apk路径] [配置文件路径]
set -euo pipefail
cd "$(dirname "$0")/.."

APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
CONF="${2:-app/src/main/assets/default_config.json}"
PKG=dev.hdwatch.buttonmap

adb devices -l | grep -v emulator | grep -qw device || {
  echo "没有可用的实机。先连接：手表开发者模式 → USB 或无线 adb（adb pair <码>）。"; exit 1; }

S=$(adb devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator/ {print $1; exit}')
echo "==> 目标设备: $S"

echo "==> HID Device profile 支持情况（关键先验）"
PROP=$(adb -s "$S" shell getprop bluetooth.profile.hid.device.enabled | tr -d '\r')
echo "    bluetooth.profile.hid.device.enabled = '${PROP:-<空>}'"
[ "$PROP" = "true" ] || echo "    !! 非 true：Samsung 可能未开放手表做 HID 外设，届时只能走日志模式验证 UI/引擎"

echo "==> 安装 $APK"
adb -s "$S" install -r "$APK" >/dev/null && echo "    ok"

echo "==> 授予蓝牙权限"
for p in BLUETOOTH_CONNECT BLUETOOTH_ADVERTISE BLUETOOTH_SCAN; do
  adb -s "$S" shell pm grant $PKG android.permission.$p 2>/dev/null || true
done
echo "    ok"

EXT="/sdcard/Android/data/$PKG/files"
echo "==> 推送外部配置 $EXT/hdmap.json（改这个文件即可换宏，重启 app 或走菜单'重载'前它会自动导入）"
adb -s "$S" shell mkdir -p "$EXT"
adb -s "$S" push "$CONF" "$EXT/hdmap.json" >/dev/null

echo "==> 启动"
adb -s "$S" shell am force-stop $PKG
adb -s "$S" shell am start -n $PKG/.MainActivity >/dev/null

echo
echo "手动步骤（实机三标定）:"
echo " 1) app 蓝牙HID屏 → 请求可发现 → Mac 系统设置里配对 'HD Watch Remote'；pad 状态点变绿即通"
echo " 2) 转表盘：日志屏看 ROT 行的正负与频率 → 设置里调 rotateThreshold / 反转正负"
echo " 3) 侧键与手势：设置里开 gestureSensorsEnabled，日志屏观察哪些 KEY/SNS 行到达"
echo
echo "日志窗口: adb -s $S logcat -s HDMAP:D"
echo "按键注入: adb -s $S shell input keyevent 265   # STEM_1"
