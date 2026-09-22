# HDWatchButtonMap

A Wear OS remote that turns a smartwatch into a programmable Bluetooth HID
keyboard/mouse. Two profile kinds, a dial-shaped control surface, crown and
gesture inputs, and a JSON config you can edit on the watch or push over adb.

Tested on Galaxy Watch6 Classic (SM-R960); minSdk 33.

## What it does

- **Bluetooth HID host link** — the watch registers as a keyboard + mouse +
  consumer-control device. No companion app on the host side.
- **Two profile kinds**
  - **Direct** — every mapped input fires immediately (keys, combos, mouse
    buttons, wheel, media keys).
  - **Macro** — inputs buffer into sequences; a completed sequence runs a
    macro made of key taps, held keys, typed text, mouse actions and delays.
- **Inputs**
  - four arc keycaps on the dial (up / down / left / right),
  - eight ring slots around them: crown CW, crown CCW, stem, stem-long, and
    four slots that can run any step,
  - the rotary bezel/crown (a per-profile detent threshold decides how many
    clicks make one step),
  - optional sensor gestures (wrist tilt, wrist-down, IMU shake) when the
    device exposes them; each is bindable to a slot.
- **Hold steps** — `{"hold":"CTRL"}` latches a key on the first press and
  lifts it on the next. Holds are also dropped automatically when a sequence
  lands, times out, or the pad is closed, so nothing stays stuck on the host.
- **Keep-alive foreground service** so the HID registration survives leaving
  the UI (the Bluetooth stack unregisters background apps otherwise).
- **Offline** — the app requests no network permission.

## Install

Grab `app-release.apk` from the latest release and sideload it:

```bash
adb install -r app-release.apk
```

The release APK is signed with a debug keystore — this is a personal project,
not a Play Store build.

## Build

```bash
gradle :app:assembleDebug     # debug
gradle :app:assembleRelease   # debug-signed release
```

## Configuration

- **On the watch**: menu → mappings / macros / Bluetooth HID / settings.
  Profiles, rotary thresholds, gesture bindings, vibration and the keep-alive
  switch all live there.
- **From a computer**: `adb push my.json
  /sdcard/Android/data/dev.hdwatch.buttonmap/files/hdmap.json` — the file is
  imported automatically whenever it is newer than the last import.
- A neutral default ships with the app; the release also carries an example
  config you can import as-is.

Config sketch:

```json
{
  "version": 2,
  "settings": { "rotateThreshold": 3, "sequenceTimeoutMs": 3000 },
  "activeProfile": "macro",
  "profiles": [
    {
      "id": "macro", "name": "Macros", "kind": "MACRO",
      "single": { "CCW": { "hold": "CTRL" }, "CW": { "mouse": { "wheel": -1 } } },
      "macros": [
        { "id": "paste", "name": "Paste", "seq": "D S",
          "steps": [ { "key": "V", "mods": ["ctrl"] } ] }
      ]
    }
  ]
}
```

## 中文说明

把手表当作蓝牙 HID 键盘/鼠标用的可编程遥控器。

- **两种模式**：直控（按下即发）与宏（按键序列触发一串动作）。
- **输入**：表盘四向弧键帽、环绕八个槽位（表冠正/反转、表冠键短/长按、四个可自定义槽）、表冠旋转（每个模式可单独设阈值）、可选手势（抬腕/腕下垂/抖动）。
- **锁存步骤**：`{"hold":"CTRL"}` 第一次按下按住、再按一次松开；序列触发、超时或退出盘面时自动释放。
- **保活前台服务**：退到后台也不掉 HID 注册。
- **配置**：表上直接改，或 `adb push` 一个 JSON 到应用外部目录自动导入；release 附带示例配置。
- 完全离线，不申请网络权限。

## License

No license file yet — all rights reserved by the author unless stated
otherwise.
