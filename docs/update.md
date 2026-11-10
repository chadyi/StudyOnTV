# 版本检查与更新提示

客户端回到学习页时通过蒲公英应用内更新接口检查，**有新版才提示**，结果缓存 6 小时。检查使用内置的蒲公英 appKey 和当前版本，不使用账户 API Key；当前版本不再访问 GitHub API、`update.json` 或第三方镜像。需要立即检查时，在家长面板「设置 → 版本更新」点「检查更新」。

检查结果的关键字段：

| 字段 | 用途 |
|---|---|
| `haveNewVersion` | 是否有新版本 |
| `version` | 最新版本号 |
| `downloadURL` | 蒲公英安装页地址，客户端直接下载 / 打开 |
| `releaseNote` | 更新说明，显示在提示里 |
| `needForceUpdate` | 是否强制更新 |

## 两种提示

| 类型 | 触发 | 表现 |
|---|---|---|
| 强制更新 | `needForceUpdate` 为 true | 全屏页，返回键无效，只能点「立即更新」 |
| 普通更新 | 版本号更高但没有强制更新标记 | 右上角小卡片，可关闭 |

## 发版流程

```bash
# 1. 改版本号（app/native/app/build.gradle.kts 里的 versionCode / versionName）
# 2. 打包两个正式包（签名读取 app/native/local.properties）
cd app/native && gradle assembleTvRelease assembleMobileRelease
# 产物在 app/build/outputs/apk/tv/release/ 与 app/build/outputs/apk/mobile/release/

# 3. 先把产物重命名，再发布 GitHub Release（供手动下载）
VERSION=0.3.21   # 与 build.gradle.kts 的 versionName 一致
gh release create "v$VERSION" \
  "StudyOnTV-Android-TV-v$VERSION.apk" "StudyOnTV-Mobile-v$VERSION.apk" \
  --title "v$VERSION" \
  --notes "· 修复……"
```

应用内更新以蒲公英后台的版本和「强制更新」设置为准；需要让应用内更新生效时，还要把对应 APK 上传到蒲公英。客户端最多缓存 6 小时，发版后可用家长面板的「检查更新」立即刷新。

### 关于 update.json

`scripts/make-update-manifest.mjs` 仍可生成 `update.json` 清单（从 `versionName` 和 `release-artifacts/` 读取），但当前客户端只走蒲公英检查，不读取该文件；它保留给手动或旧版流程使用。
