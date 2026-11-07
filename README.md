# MIX FLIP2外屏增强 · MIX Flip 2 Cover Enhancer

[![Build](https://github.com/jabe1122/MixFlip2Cover/actions/workflows/build.yml/badge.svg)](https://github.com/jabe1122/MixFlip2Cover/actions/workflows/build.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

一个专为 **小米 MIX Flip 2**（HyperOS 4）外屏（1208×1392）打造的 LSPosed 增强模块。

[English](#english)

> [!WARNING]
> 本模块会 Hook `system_server`、系统界面和外屏桌面，**仅支持 MIX Flip 2（代号 `bixi`）上的 HyperOS 4**。
> 其他机型或系统版本会自动停用，不安装任何 Hook。刷机或修改系统有风险，请先做好备份。

## 功能

| 功能 | 说明 |
|---|---|
| 外屏应用全屏与去留白 | 在系统框架层处理外屏应用的状态栏 Insets，隐藏原生状态栏并右移隐私绿点；统一缩减外屏应用顶部至 30px 留白（兼顾全屏视觉与顶部点击），展开内屏自动恢复。 |
| 外屏状态圈 | 在外屏镜头上方显示紧凑的时间、电量环（可选电量百分比与充满插头）、单/双卡信号、充电、飞行模式、热点等状态，颜色跟随系统深浅色；应用请求全屏（如相机）时自动隐藏。 |
| 超级岛与小组件适配 | 超级岛焦点通知与官方柔光玻璃小组件共用状态圈下方的方形区域，保留原生层级、点击和滑动，固定比例内容等比缩放适配。 |
| 小组件固定常用应用 | 支持将外屏 2×2 应用图标小组件（最近任务小组件）自定义固定最多 **4 个常用应用**；按勾选顺序排列，未满 4 个时自动用不重复的最近任务补齐，无后台时亦常驻显示，支持实时同步与原生动画直接启动。 |
| 外屏桌面与通知手势 | 状态圈与首排图标对齐，五排对称图标网格，支持隐藏桌面应用名称；顶部左 1/3 下拉通知栏，右 2/3 下拉控制中心；消除外屏下拉通知栏顶栏与悬浮弹出通知（Heads-Up）的顶部状态栏留白。 |
| 键盘自动避让 | 弹出键盘重叠时，状态圈与方形卡片自动隐藏，收起键盘后自动恢复。 |
| 外屏打开所有应用（默认开启，可关） | 解除系统“展开内屏继续操作”限制，允许在外屏直接打开系统设置等被拦截的应用。 |
| 外屏通知直接打开（默认开启，可关） | 解除外屏点击通知弹出“在内屏查看”的限制，直接在外屏打开对应应用。 |
| 原生外屏适配页面保留 | 对声明了 `miui.supportFlipFullScreen=0` 的页面（如网易云音乐外屏播放页）保留其原生布局、真实挖孔和原始像素密度。 |
| 深度应用兼容 | 针对超级小爱（外屏唤醒全屏铺满与镜头/状态圈避让）、微信、QQ、美团、小米商城、小米运动健康、网易云音乐、系统日历、系统设置等应用的顶栏/底栏与外屏布局专项适配。 |
| 外屏微信输入法适配（可选，**默认关闭**） | 专门针对微信输入法（WeType 4.0.x）适配外屏高度、摄像头半圆避让与留白，并绕过系统强制切换搜狗输入法。 |

## 环境要求

- 小米 MIX Flip 2（`bixi`），HyperOS 4（Android 16 / SDK 36）
- 已 Root，并安装支持 **libxposed API 101** 的 LSPosed（现代 API）

## 安装

1. 从 [Releases](https://github.com/jabe1122/MixFlip2Cover/releases) 或 [LSPosed 模块仓库](https://modules.lsposed.org/module/io.github.jabe1122.mixflip2cover) 下载 APK 并安装。
2. 在 LSPosed 管理器中启用模块。核心作用域已预置：`系统框架 (system)`、`系统界面 (com.android.systemui)`、`外屏桌面 (com.miui.fliphome)`、`超级小爱 (com.miui.voiceassist)`。
3. **重启手机**（系统框架 Hook 需要重启后才生效）。
4. 打开 App，确认首页显示“运行正常 / 框架已就绪”。

需要深度应用专项兼容（微信、QQ、美团、网易云音乐等）或微信输入法外屏适配时，可在 App 的“兼容”页管理扩展应用作用域，添加后重启对应应用即可生效。

## 外屏微信输入法适配（可选）

HyperOS 在外屏会强制切回系统搜狗输入法。开启此功能后，模块会：

- 停用系统搜狗输入法外屏强制切换逻辑，保持你的默认输入法；
- 针对微信输入法（`com.tencent.wetype` 4.0.x）适配外屏键盘高度、摄像头半圆避让与左侧可用区宽度。

开启后，请在系统设置中将默认输入法切换为「微信输入法」，并在模块 App 中将「微信输入法」加入扩展作用域后重启输入法。

## 卸载 / 恢复系统默认

模块对系统做的持久修改（状态栏尺寸覆盖层、停用的搜狗外屏输入法组件、外屏输入法设置）会在以下时机自动撤销：

- 每次正常关机或重启时；
- 关闭“外屏微信输入法适配”时；
- 在 App 中点击“恢复系统默认”时。

因此，推荐的卸载流程是：先在 App 中点击“恢复系统默认”，再到 LSPosed 停用或卸载模块，最后重启手机。

如果手机异常断电，或者模块已经停用无法打开，可以通过 ADB 手动恢复：

```sh
adb shell cmd overlay disable --user 0 android:mixflip2cover_statusbar
adb shell cmd overlay disable --user 0 android:flipduo_cover_statusbar_v1
adb shell cmd overlay disable --user 0 com.android.systemui:flipduo_cover_statusbar_v1
adb shell pm enable com.sohu.inputmethod.sogou.xiaomi/com.sohu.inputmethod.sogou.xiaomi.SogouIME
adb shell settings put secure default_input_method com.sohu.inputmethod.sogou.xiaomi/.SogouIME
```

## 构建

```sh
./gradlew :app:assembleRelease
```

- 需要 JDK 17 以上（推荐 21）和 Android SDK 36。
- 正式签名从 `keystore.properties`（已加入 `.gitignore`）或环境变量 `MF2C_STORE_FILE` / `MF2C_STORE_PASSWORD` / `MF2C_KEY_ALIAS` / `MF2C_KEY_PASSWORD` 读取；两者都没有时自动回退到 debug 签名。

## 反馈问题

可在 App 的 **「兼容 → 高级 → 查看与导出诊断日志」** 中直接查看、一键复制或分享包含机型、系统版本、作用域及跨进程运行日志的完整诊断报告；也可以通过 ADB 抓取日志：

```sh
adb logcat -s MF2C.Framework MF2C.Compat MF2C.ImeGuard MF2C.Overlay MF2C.SystemUI MF2C.PinnedWidget LSPosed
```

## 许可证

[GPL-3.0](LICENSE) © jabe1122

---

## English

An LSPosed module (libxposed API 101) that enhances the **Xiaomi MIX Flip 2** (`bixi`) cover screen (1208×1392) on **HyperOS 4**.

**Supported:** MIX Flip 2 (`bixi`) on HyperOS 4 only. On any other device or OS version the module stays inactive and installs no hooks.

**Features:**
- **Fullscreen Cover Apps & Top-Gap Removal:** Handles status bar insets in the framework and unifies the top app gap to 30px while keeping top touch targets responsive; restores automatically when unfolded.
- **Cover Status Orb:** Compact status orb above the cameras showing time, battery ring (optional percentage & full-charge plug), single/dual-SIM signal dots, charging, airplane mode, and hotspot, following system light/dark themes.
- **Super Island & Widget Adaptation:** Fits HyperOS focus notifications (Super Island) and stock frosted-glass widgets into the square area below the status orb.
- **Pin Favorite Apps in Cover Widget:** Customize the 2×2 cover app-icon widget (`RecentTaskView`) by pinning up to **4 favorite apps** in your chosen order; unfilled slots auto-fill with non-duplicate recent tasks, and pinned icons stay visible even when recents are empty, with live sync and native launch animations.
- **Cover Launcher, Gestures & Compact Notifications:** Five-row symmetric icon grid aligned with the status orb, optional app label hiding, split top pull-down (left 1/3 for notifications, right 2/3 for Control Center), and top status-bar blank-space removal for both the pulled-down Notification Shade and Heads-Up pop-up notifications.
- **App & Notification Unblocking:** Opens restricted apps (such as Settings) and notification taps directly on the cover screen without the "unfold / view on inner screen" prompt (enabled by default, configurable).
- **Native Cover Layouts Preserved:** Activities declaring `miui.supportFlipFullScreen=0` (e.g., NetEase Cloud Music cover player) keep their native layout, cutout, and density.
- **Deep App & WeType Keyboard Adaptation:** Fullscreen cover overlay & camera/orb avoidance for Super XiaoAi (`com.miui.voiceassist`), dedicated layout adapters for WeChat, QQ, Meituan, Xiaomi Shop, Mi Fitness, NetEase Cloud Music, Calendar, and Settings, plus optional cover keyboard adaptation for WeChat Input Method (`WeType` 4.0.x, off by default).

**Install:** Install the APK, enable the module in LSPosed (core scopes `system`, `com.android.systemui`, `com.miui.fliphome`, and `com.miui.voiceassist` are preset), reboot, and verify that the app reports "Running".

**Uninstall:** Tap *Restore system defaults* in the app, disable or uninstall the module in LSPosed, then reboot. Persistent changes are also reverted automatically on every shutdown. Manual fallback:

```sh
adb shell cmd overlay disable --user 0 android:mixflip2cover_statusbar
adb shell cmd overlay disable --user 0 android:flipduo_cover_statusbar_v1
adb shell cmd overlay disable --user 0 com.android.systemui:flipduo_cover_statusbar_v1
adb shell pm enable com.sohu.inputmethod.sogou.xiaomi/com.sohu.inputmethod.sogou.xiaomi.SogouIME
adb shell settings put secure default_input_method com.sohu.inputmethod.sogou.xiaomi/.SogouIME
```

**License:** GPL-3.0.
