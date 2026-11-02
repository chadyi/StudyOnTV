# 安装

两步：**先开 ADB 调试，再装包**。开不了 ADB 的电视，直接看 [U 盘](#方式-du-盘不用-adb)。

---

## 第一步：开启 ADB 调试

**1. 打开开发者选项**

设置 → 关于 / 关于本机 → 连点「版本号」或「内部版本号」**7 次** → 返回上一级，出现「开发者选项」。

**2. 打开调试开关**

进入开发者选项 → 打开 **USB 调试**；想走无线，再把 **网络调试 / ADB over network** 打开。

**3. 记下电视 IP**

设置 → 网络 / 关于 → 类似 `192.168.1.100` 的地址。

### 三种连接方式

| 方式 | 电视上要开的 | 电脑上执行 |
|---|---|---|
| **无线**（推荐） | USB 调试 + 网络调试 | `adb connect 192.168.1.100:5555` |
| **有线** | USB 调试 | USB 双公头线（A-A）连电脑与电视 → `adb devices` |
| **配对码**（Android 11+） | 开发者选项 → 无线调试 → 使用配对码配对 | `adb pair 192.168.1.100:37861` → 输入电视上的 6 位码 → `adb connect 192.168.1.100:<端口>` |

第一次连接时电视会弹「允许 USB 调试 / 允许网络调试」，**点确定**，否则后面一直是 `unauthorized`。

### 各品牌开 ADB 的位置

| 品牌 | 路径 |
|---|---|
| 小米 / Redmi | 设置 → 关于 → 连点「产品型号」→ 开发者选项 |
| TCL / 雷鸟 | 设置 → 关于 → 本机信息 → 遥控器按「上 下 左 右」→ 底部「更多」→ ADB |
| 索尼 | 设置 → 设备偏好设置 → 关于 → 连点「内部版本号」7 次 |
| 海信 / Vidda | 关于页面开开发者选项，部分型号有专门菜单 |
| 创维 / 酷开 | 关于页面或开发者菜单 |
| 康佳 / 长虹 | 按具体型号找开发者选项 |
| OPPO | 设置 → 系统 → 关于本机 → 设备型号 + 按键组合 |

---

## 第二步：装包

### 方式 a：交给 Agent

复制下面这段发给任意能执行命令的 AI Agent（Claude Code、Cursor、Codex、通义灵码、WorkBuddy 等都行）：

```
帮我在这台电视上安装 StudyOnTV。ADB 调试已经开好了。
电视 IP：<填>

1. 从 https://github.com/chadyi/StudyOnTV/releases/latest 下载最新 APK
2. adb connect <IP>:5555，然后 adb devices 确认设备出现；
   没出现就等我到电视上点「允许」，还是不行再告诉我怎么处理
3. adb install -r <刚下载的 apk>
4. adb shell monkey -p com.studyontv.tv -c android.intent.category.LAUNCHER 1 打开应用
5. 最后列出我还需要手动开的权限：无障碍、显示在其他应用上层、使用情况访问、自启动

报错你先自己排查并修复；确实修不了，再把错误原文和你建议我自己动手的解决办法一起告诉我。
```

### 方式 b：手动

```bash
adb connect 192.168.1.100:5555     # 换成电视 IP
adb devices                        # 出现 device 才算成功
adb install -r StudyOnTV.apk       # 看到 Success 就成了
adb shell monkey -p com.studyontv.tv -c android.intent.category.LAUNCHER 1
```

装完把 ADB 调试关掉，减少暴露。

### 方式 c：现成工具（图形界面）

一些把 ADB 图形化的工具，可以可视化地安装 APP。

| 工具 | 跑在哪 | 许可 | 特点 |
|---|---|---|---|
| [tvhelper-docker](https://github.com/wukongdaily/tvhelper-docker) | Docker（电脑 / NAS / 软路由 / OpenWrt） | GPL-3.0 | 网页上传装包、批量装、支持 XAPK / APKM |
| [SmartTV-Toolkit](https://github.com/xiazheteng8/SmartTV-Toolkit) | Windows | 未标注许可（只参考思路，别抄代码） | 国产电视品牌适配、扫已装应用、投屏遥控 |
| [android-tv-toolkit](https://github.com/VictorTran1023/android-tv-toolkit) | ADB 工具箱 | MIT | 各品牌开 ADB 的路径、`adb unauthorized` 重握手、分品牌安装通道 |
| [Android-TV-Desktop-Toolkit](https://github.com/deepsadness/Android-TV-Desktop-Toolkit) | Windows / macOS | AGPL-3.0 | 图形化装包、投屏录屏、电脑当遥控器 |
| [TvToolsPro](https://github.com/deekshith0509/TvToolsPro) | 手机 | 实验性 | 手机无线 ADB 装包，不用电脑 |

### 方式 d：U 盘（不用 ADB）

1. U 盘格式化成 **NTFS**，APK 放根目录
2. 插电视 → 打开文件管理器 / 媒体播放器 → 点 APK 安装
3. 提示「未知来源」→ 去设置里允许，再装一次

⚠️ 部分海信、创维、华为智慧屏**禁止安装第三方应用**，无法使用U 盘安装，只能用 ADB。

---

## 装完必开的权限

| 权限 | 位置 | 不开的后果 |
|---|---|---|
| 无障碍服务 | 设置 → 无障碍 → 已下载的服务 → StudyOnTV | 检测不到孩子打开了哪个应用 |
| 显示在其他应用上层 | 设置 → 应用 → 特殊权限 | 时间到了盖不住画面 |
| 使用情况访问 | 设置 → 应用 → 特殊权限 → 使用情况访问 | 无障碍被清理后没有兜底 |
| 自启动 / 后台白名单 | 设置 → 应用 → 自启动管理 | 重启后守护服务不工作 |

四个都开完，手机上扫码（或输 6 位配对码）绑定电视。

---

## 装不上

| 现象 | 原因 | 处理 |
|---|---|---|
| 「应用未安装」/ 解析包错误 | 系统低于 Android 5.0 | 暂不支持，后续支持 |
| 没有「未知来源」开关 | 各家菜单不同 | 设置里搜「安全」「安装」 |
| `adb connect` 拒绝 / 超时 | 没开网络调试 / 端口不同 | 回第一步第 2 步 |
| `adb unauthorized` 且电视不弹窗 | 厂商 adbd 公钥格式特殊 | 用 android-tv-toolkit 的重握手流程 |
| U 盘插上没反应 | 文件管理器不认 | 换 NTFS U 盘，或改用 ADB |
| 装上闪退 | 电视 WebView 版本过老 | 群里反馈型号 |
| 华为鸿蒙 NEXT / 三星 Tizen / LG webOS | 不是 Android 系统 | 后续支持 |

---

## 系统版本

| 系统 | 状态 |
|---|---|
| Android 5.0 及以上 | ✅ 支持 |
| Android 4.x 及以下 | ⏳ 待支持 |
| 鸿蒙 NEXT / Tizen / webOS | ⏳ 待支持 |
