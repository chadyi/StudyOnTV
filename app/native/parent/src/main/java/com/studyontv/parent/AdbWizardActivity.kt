package com.studyontv.parent

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.studyontv.parent.AdbWizardViews.ApkSource
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 无线 ADB（TCP 5555）一键装电视 & 权限自动配置向导：
 * 1. 分品牌 3 步开启 ADB 速查卡；
 * 2. 局域网 5555 并发快扫 + 手动输入 IP / IP:端口；
 * 3. 模式切换：「全新安装/升级 + 自动配权限」vs「仅一键修复权限」；
 * 4. APK 来源：自动下载最新电视版 / 手机平板版（蒲公英优先 + GitHub 回退）或选取手机本地 APK；
 * 5. 逐项实时进度反馈，电视弹授权框时高亮提醒「按遥控器点一律允许」，完成后一键直达管理后台。
 */
class AdbWizardActivity : Activity() {
    companion object {
        const val EXTRA_PREFILL_IP = "prefill_ip"
        const val EXTRA_FIX_ONLY = "fix_only"
        private const val REQ_PICK_APK = 5201
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val scanning = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    private lateinit var views: AdbWizardViews

    private var mode = TvProvisioner.Mode.INSTALL_AND_CONFIGURE
    private var apkSource = ApkSource.AUTO_TV
    private var localApkFile: File? = null
    private var localApkLabel: String = ""
    private var lastDiscovered: LanNet.DiscoveredDevice? = null

    private val stepOrder = ArrayList<String>()
    private val stepMap = LinkedHashMap<String, TvProvisioner.StepUpdate>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ParentDiag.init(this)
        val palette = ParentUi.palette(this)
        ParentUi.applySystemBars(this, palette)

        val prefillIp = intent.getStringExtra(EXTRA_PREFILL_IP).orEmpty().trim()
        if (intent.getBooleanExtra(EXTRA_FIX_ONLY, false)) {
            mode = TvProvisioner.Mode.FIX_PERMISSIONS_ONLY
        }

        views = AdbWizardViews(
            activity = this,
            p = palette,
            prefillIp = prefillIp,
            callbacks = object : AdbWizardViews.Callbacks {
                override fun onBack() = finish()
                override fun onScanAdb() = triggerAdbScan()
                override fun onSelectMode(mode: TvProvisioner.Mode) {
                    if (!running.get()) {
                        this@AdbWizardActivity.mode = mode
                        syncModeAndSource()
                    }
                }

                override fun onSelectApkSource(source: ApkSource) {
                    if (!running.get()) {
                        apkSource = source
                        syncModeAndSource()
                        if (source == ApkSource.LOCAL_FILE) pickLocalApk()
                    }
                }

                override fun onStartProvisioning() = startProvisioning()
                override fun onOpenConsole() = openDiscoveredConsole()
            }
        )
        setContentView(views.rootView)
        views.renderBrandGuide()
        syncModeAndSource()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun syncModeAndSource() {
        views.renderModeAndSource(
            mode = mode,
            apkSource = apkSource,
            localApkLabel = localApkLabel,
            hasLocalApk = localApkFile != null,
            isRunning = running.get()
        )
    }

    private fun openDiscoveredConsole() {
        val d = lastDiscovered ?: return
        startActivity(
            Intent(this, DeviceConsoleActivity::class.java)
                .putExtra(DeviceConsoleActivity.EXTRA_DEVICE_ID, d.id)
                .putExtra(DeviceConsoleActivity.EXTRA_DEVICE_NAME, d.name)
                .putExtra(DeviceConsoleActivity.EXTRA_IP, d.ip)
                .putExtra(DeviceConsoleActivity.EXTRA_PORT, d.port)
        )
    }

    private fun triggerAdbScan() {
        if (!scanning.compareAndSet(false, true)) return
        views.setScanningState(scanning = true, statusText = "正在扫描 5555 端口（约 3 秒）…", clearCandidates = true)

        worker.execute {
            val found = try {
                LanNet.scanAdbDevices()
            } catch (_: Exception) {
                emptyList()
            } finally {
                scanning.set(false)
            }
            runOnUiThread {
                val status = if (found.isEmpty()) "未扫到 5555 端口，可手动输入 IP" else "发现 ${found.size} 台设备"
                views.setScanningState(scanning = false, statusText = status)
                views.renderCandidates(found)
            }
        }
    }

    private fun pickLocalApk() {
        val candidates = listOf(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            },
            Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
        )
        for (intent in candidates) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQ_PICK_APK)
                return
            } catch (_: Exception) { }
        }
        ParentDiag.warn("AdbWizard", "pickLocalApk failed: no file picker available")
        Toast.makeText(this, "当前系统无法打开文件选择器", Toast.LENGTH_SHORT).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_APK && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            Toast.makeText(this, "正在校验本地 APK…", Toast.LENGTH_SHORT).show()
            worker.execute {
                try {
                    val file = ReleaseApkFetcher.copyLocalApk(this, uri)
                    val mb = String.format(Locale.US, "%.1f MB", file.length() / (1024.0 * 1024.0))
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "local.apk"
                    runOnUiThread {
                        localApkFile = file
                        localApkLabel = "$name（$mb）"
                        apkSource = ApkSource.LOCAL_FILE
                        syncModeAndSource()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, e.message ?: "读取 APK 失败", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun startProvisioning() {
        val parsed = LanNet.parsePrivateLanHost(views.currentIpInput(), LanNet.DEFAULT_ADB_PORT)
        if (parsed == null) {
            Toast.makeText(this, "请输入有效的局域网电视 IP（例如 192.168.31.66）", Toast.LENGTH_LONG).show()
            return
        }
        if (mode == TvProvisioner.Mode.INSTALL_AND_CONFIGURE && apkSource == ApkSource.LOCAL_FILE && localApkFile == null) {
            Toast.makeText(this, "请先选择手机里的 .apk 安装包，或切换为在线自动下载", Toast.LENGTH_LONG).show()
            return
        }
        if (!running.compareAndSet(false, true)) return

        stepOrder.clear()
        stepMap.clear()
        lastDiscovered = null
        views.beginProgress()

        val activeMode = mode
        val activeSource = apkSource
        val chosenLocal = localApkFile

        worker.execute {
            ParentDiag.info("AdbWizard", "start mode=$activeMode source=$activeSource target=${parsed.host}")
            try {
                val (apkToInstall, preferredFlavor) = resolveInstallArtifact(activeMode, activeSource, chosenLocal)
                val crypto = AdbCrypto.loadOrCreate(this)
                val result = TvProvisioner.provision(
                    ip = parsed.ip,
                    port = parsed.port,
                    mode = activeMode,
                    apkFile = apkToInstall,
                    preferredFlavor = preferredFlavor,
                    crypto = crypto,
                    listener = object : TvProvisioner.Listener {
                        override fun onStep(update: TvProvisioner.StepUpdate) {
                            if (update.id == "connect" && update.state == TvProvisioner.StepState.OK) {
                                runOnUiThread { views.setApprovalBannerVisible(false) }
                            }
                            postStep(update)
                        }

                        override fun onWaitingForTvApproval(ip: String) {
                            runOnUiThread { views.setApprovalBannerVisible(true, scroll = true) }
                        }

                        override fun onPushProgress(percent: Int) {
                            // 进度已通过 onStep("push") 推送
                        }
                    }
                )

                result.discoveredDevice?.let { found ->
                    DeviceStore.recordDiscovered(this, listOf(found))
                }

                ParentDiag.info("AdbWizard", "done success=${result.success} summary=${result.summary.take(160)}")
                runOnUiThread {
                    lastDiscovered = result.discoveredDevice
                    views.showSummary(
                        success = result.success,
                        text = result.summary,
                        showOpenConsole = result.discoveredDevice != null,
                        scroll = true
                    )
                }
            } catch (e: Exception) {
                val msg = e.message ?: "执行失败"
                ParentDiag.error("AdbWizard", "failed target=${parsed.host} msg=$msg", e)
                postStep(TvProvisioner.StepUpdate("fatal", "准备阶段失败", TvProvisioner.StepState.FAIL, msg))
                runOnUiThread {
                    views.showSummary(success = false, text = msg, showOpenConsole = false)
                }
            } finally {
                running.set(false)
                runOnUiThread { syncModeAndSource() }
            }
        }
    }

    private fun resolveInstallArtifact(
        activeMode: TvProvisioner.Mode,
        activeSource: ApkSource,
        chosenLocal: File?
    ): Pair<File?, TvProvisioner.TargetFlavor?> {
        if (activeMode != TvProvisioner.Mode.INSTALL_AND_CONFIGURE) return null to null
        if (activeSource == ApkSource.LOCAL_FILE) {
            val pkg = chosenLocal?.let {
                @Suppress("DEPRECATION")
                packageManager.getPackageArchiveInfo(it.path, 0)?.packageName
            }
            val flavor = when (pkg) {
                TvProvisioner.PKG_TV -> TvProvisioner.TargetFlavor.TV
                TvProvisioner.PKG_MOBILE -> TvProvisioner.TargetFlavor.MOBILE
                else -> null
            }
            return chosenLocal to flavor
        }
        val flavor = if (activeSource == ApkSource.AUTO_TV) {
            TvProvisioner.TargetFlavor.TV
        } else {
            TvProvisioner.TargetFlavor.MOBILE
        }
        postStep(TvProvisioner.StepUpdate("download", "查询最新「${flavor.label}」安装包", TvProvisioner.StepState.RUNNING))
        val meta = ReleaseApkFetcher.fetchLatestMeta(flavor)
        postStep(TvProvisioner.StepUpdate("download", "下载 v${meta.version}（${meta.source}）", TvProvisioner.StepState.RUNNING, "0%"))
        val downloaded = ReleaseApkFetcher.downloadApk(this, meta) { pct ->
            postStep(TvProvisioner.StepUpdate("download", "下载 v${meta.version}（${meta.source}）", TvProvisioner.StepState.RUNNING, "$pct%"))
        }
        val sizeMb = String.format(Locale.US, "%.1f MB", downloaded.length() / (1024.0 * 1024.0))
        postStep(
            TvProvisioner.StepUpdate(
                "download",
                "安装包已就绪：v${meta.version}（$sizeMb）",
                TvProvisioner.StepState.OK,
                "来源：${meta.source}"
            )
        )
        return downloaded to flavor
    }

    private fun postStep(update: TvProvisioner.StepUpdate) {
        runOnUiThread {
            if (update.id !in stepMap) stepOrder.add(update.id)
            stepMap[update.id] = update
            views.renderSteps(stepOrder, stepMap)
        }
    }
}
