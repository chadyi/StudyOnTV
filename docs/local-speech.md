# 本地朗读与覆盖升级

基于 v0.5.0，保留上游原有语音服务，不增加自定义接口地址或语音服务商。

## 行为变化

- 本地 TTS 将短句先合成为 WAV，再由答题 App 播放 PCM，减少独立引擎被厂商清理时的播放中断。合成连接断开时有超时及一次重试。
- 防沉迷服务不再将本 App 的朗读误判为后台视频而暂停或静音。重复渲染同一道题也不会反复打断自动朗读。
- 本地中文默认回落普通话。数字、运算符会转换成中文，例如 `18-18=` 读作「十八减十八等于多少」；不改题库文字或答案，显式英语朗读不进行中文转换。
- 题目左侧显示大扬声器按钮，每道新题默认聚焦它；遥控器确定朗读，方向键继续选答案。自动朗读设置保留，可在管理面板关闭。
- 管理面板的测试朗读等待实际播放状态或错误，避免合成尚未完成就提示成功。
- 旧个人测试包中已删除的自定义语音配置会回落本地通路；旧密钥不会返回管理面板，下一次保存设置时清理。其他朗读选项与服务密钥保留。

## 升级保留数据

Android 覆盖升级保留配置、题库和答题记录，需要同一包名、同一签名及不降低的 versionCode。安装脚本只运行 `adb install -r`，不卸载、不清数据；安装前备份数据库、配置及媒体文件到 `.local-backups/`，错误时停止。

已安装的个人 TV 测试包保持原包名和签名，相关私有密钥、备份均排除在 Git 之外。上游默认构建配置、版本号与应用名称保持不变。卸载后重装不属于覆盖升级，仍会删除 Android 应用私有数据。

## 构建与安装

要求 Node.js/npm、JDK 17+、Android SDK 36、Gradle 8.14.3。

先在 `parent-ui` 执行 `npm ci`、`npm run build`；再在 `app/native` 执行：

```text
gradle testTvDebugUnitTest testMobileDebugUnitTest assembleTvDebug assembleMobileDebug
```

TV APK 位于 `app/native/app/build/outputs/apk/tv/debug/app-tv-debug.apk`。覆盖安装脚本要求 PowerShell 7：

```powershell
./scripts/install-personal-tv.ps1 -Device <电视IP>:5555
```

## 验证

- JVM 单元测试覆盖中文数字、加减乘除、负数、小数、百分数、简单分数及英语保留。
- `node scripts/test-reading-ui.mjs` 验证 720p/1080p 图标尺寸、位置、初始/换题焦点、确定朗读和方向键移动。用 `CHROME_PATH` 指定 Chromium 浏览器。
- 真机音频回归：先选择本地系统语音并安装 androidTest APK，运行下面的 instrumentation。`localSpeech` 会朗读固定长句，`mathSpeech` 朗读固定测试算式；均不修改家长设置或题库。`speechUpgrade` 使用独立数据库验证旧配置清理与其他设置保留。

```powershell
adb -s <电视IP>:5555 shell am instrument -w -e localSpeech 1 com.studyontv.tv.debug.test/com.studyontv.tv.RegressionInstrumentation
adb -s <电视IP>:5555 shell am instrument -w -e mathSpeech 1 com.studyontv.tv.debug.test/com.studyontv.tv.RegressionInstrumentation
adb -s <电视IP>:5555 shell am instrument -w -e speechUpgrade 1 com.studyontv.tv.debug.test/com.studyontv.tv.RegressionInstrumentation
```
