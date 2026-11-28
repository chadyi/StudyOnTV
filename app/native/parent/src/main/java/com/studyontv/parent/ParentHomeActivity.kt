package com.studyontv.parent

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 家长端首页：
 * 1. 自动扫描并记住家里运行「题了个题」的电视与平板（支持按 deviceId 自动跟进 IP 变化）；
 * 2. 一键打开任意设备的 `/admin/` 家长管理后台；
 * 3. 一键进入「无线 ADB 一键装电视 / 修权限」向导；
 * 4. 支持手动输入局域网 IP 探测与复制电脑管理网址。
 */
class ParentHomeActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val scanning = AtomicBoolean(false)

    private lateinit var p: ParentUi.Palette
    private lateinit var subtitleView: TextView
    private lateinit var scanStatusView: TextView
    private lateinit var scanBtn: TextView
    private lateinit var updateBannerSlot: LinearLayout
    private lateinit var deviceListContainer: LinearLayout

    private var devices: List<DeviceStore.SavedDevice> = emptyList()
    private var onlineIds: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ParentDiag.init(this)
        p = ParentUi.palette(this)
        ParentUi.applySystemBars(this, p)
        devices = DeviceStore.load(this)
        setContentView(buildContentView())
        renderDevices()
        triggerLanScan(silent = false)
        checkAppUpdate()
    }

    override fun onResume() {
        super.onResume()
        devices = DeviceStore.load(this)
        updateNetworkSubtitle()
        renderDevices()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(p.bg)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = ParentUi.dp(this@ParentHomeActivity, 16)
            setPadding(pad, ParentUi.dp(this@ParentHomeActivity, 18), pad, ParentUi.dp(this@ParentHomeActivity, 28))
        }
        scroll.addView(root)

        // 顶部标题栏
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = "题了个题 · 家长端"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.textPrimary)
        })
        subtitleView = TextView(this).apply {
            textSize = 12f
            setTextColor(p.textMuted)
            setPadding(0, ParentUi.dp(this@ParentHomeActivity, 3), 0, 0)
        }
        titleCol.addView(subtitleView)
        header.addView(titleCol)
        header.addView(ParentUi.badge(this, "v${BuildConfig.VERSION_NAME}", p.accentSoft, p.accentText).apply {
            isClickable = true
            setOnClickListener { ParentDiag.showMenuDialog(this@ParentHomeActivity) }
        })
        root.addView(header)
        updateNetworkSubtitle()

        updateBannerSlot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, ParentUi.dp(this@ParentHomeActivity, 12), 0, 0)
        }
        root.addView(updateBannerSlot)

        root.addView(ParentUi.spacer(this, 14))

        // 顶部快捷操作栏：扫描 / 添加 IP / 无线安装，设备列表紧跟在下方首屏可见
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        scanBtn = ParentUi.secondaryButton(this, p, "扫描设备") {
            triggerLanScan(silent = false)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        actionsRow.addView(scanBtn)
        actionsRow.addView(ParentUi.spacer(this, heightDp = 1, widthDp = 8))
        actionsRow.addView(ParentUi.secondaryButton(this, p, "添加设备") {
            showManualAddDialog()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        actionsRow.addView(ParentUi.spacer(this, heightDp = 1, widthDp = 8))
        actionsRow.addView(ParentUi.tintedButton(this, p.accentSoft, p.accentText, "无线安装") {
            openAdbWizard(prefillIp = null, fixOnly = false)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(actionsRow)

        // 设备列表标题 & 扫描状态
        val listHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ParentUi.dp(this@ParentHomeActivity, 2), ParentUi.dp(this@ParentHomeActivity, 16), 0, ParentUi.dp(this@ParentHomeActivity, 8))
        }
        listHeader.addView(TextView(this).apply {
            text = "设备"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        scanStatusView = TextView(this).apply {
            textSize = 12f
            setTextColor(p.textMuted)
        }
        listHeader.addView(scanStatusView)
        root.addView(listHeader)

        deviceListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(deviceListContainer)

        return scroll
    }

    private fun updateNetworkSubtitle() {
        val ips = LanNet.localPrivateIpv4Addresses()
        subtitleView.text = if (ips.isNotEmpty()) "局域网 ${ips.first()}" else "未连接 Wi-Fi"
    }

    private fun checkAppUpdate() {
        ParentUpdater.check(this, forceRefresh = false) { info, _ ->
            if (info == null || ParentUpdater.isDismissed(this, info.version)) return@check
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                updateBannerSlot.removeAllViews()
                updateBannerSlot.addView(ParentUpdater.buildUpdateCard(this, p, info) {
                    updateBannerSlot.removeAllViews()
                    updateBannerSlot.visibility = View.GONE
                })
                updateBannerSlot.visibility = View.VISIBLE
            }
        }
    }

    private fun triggerLanScan(silent: Boolean) {
        if (!scanning.compareAndSet(false, true)) return
        runOnUiThread {
            scanBtn.text = "扫描中…"
            scanBtn.isEnabled = false
            scanStatusView.text = "扫描中…"
        }
        io.execute {
            try {
                // 1. UDP 广播发现
                val udpFound = LanNet.discoverStudyOnTvDevices(timeoutMs = 1200L)
                val foundMap = LinkedHashMap<String, LanNet.DiscoveredDevice>()
                for (d in udpFound) foundMap[d.id] = d

                // 2. 对已保存但未响应 UDP 的设备做一次 HTTP/HTTPS /api/hello 探测（兼容跨 AP 与手动添加的远程/域名设备）
                val savedSnapshot = DeviceStore.load(this)
                for (saved in savedSnapshot) {
                    if (saved.id in foundMap) continue
                    val probed = LanNet.probeHello(saved.ip, saved.port, timeoutMs = 1500, scheme = saved.scheme)
                    if (probed != null) foundMap[probed.id] = probed
                }

                val merged = if (foundMap.isNotEmpty()) {
                    DeviceStore.recordDiscovered(this, foundMap.values.toList())
                } else {
                    savedSnapshot
                }
                val liveIds = foundMap.keys.toSet()
                ParentDiag.info("LanScan", "udp=${udpFound.size} live=${liveIds.size} saved=${savedSnapshot.size}")

                runOnUiThread {
                    devices = merged
                    onlineIds = liveIds
                    scanBtn.text = "扫描设备"
                    scanBtn.isEnabled = true
                    scanStatusView.text = when {
                        liveIds.isNotEmpty() -> "在线 ${liveIds.size} 台"
                        merged.isNotEmpty() -> "共 ${merged.size} 台"
                        else -> ""
                    }
                    updateNetworkSubtitle()
                    renderDevices()
                    if (!silent && liveIds.isEmpty() && merged.isEmpty()) {
                        Toast.makeText(this, "未发现设备，可点「添加 IP」或「无线安装」", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                ParentDiag.warn("LanScan", "scan failed: ${e.javaClass.simpleName} ${e.message}")
                runOnUiThread {
                    scanBtn.text = "扫描设备"
                    scanBtn.isEnabled = true
                    scanStatusView.text = "扫描中断"
                }
            } finally {
                scanning.set(false)
            }
        }
    }

    private fun renderDevices() {
        deviceListContainer.removeAllViews()
        if (devices.isEmpty()) {
            val emptyCard = ParentUi.card(this, p, paddingDp = 16)
            emptyCard.addView(TextView(this).apply {
                text = "暂无设备"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(p.textPrimary)
            })
            emptyCard.addView(TextView(this).apply {
                text = "同 Wi-Fi 打开设备端「题了个题」后点上方「扫描设备」，或直接无线安装。"
                textSize = 13f
                setTextColor(p.textMuted)
                setPadding(0, ParentUi.dp(this@ParentHomeActivity, 6), 0, ParentUi.dp(this@ParentHomeActivity, 12))
            })
            emptyCard.addView(ParentUi.primaryButton(this, p, "无线 ADB 安装 / 修权限") {
                openAdbWizard(prefillIp = null, fixOnly = false)
            })
            deviceListContainer.addView(emptyCard)
            return
        }

        for ((idx, dev) in devices.withIndex()) {
            if (idx > 0) deviceListContainer.addView(ParentUi.spacer(this, 10))
            deviceListContainer.addView(buildDeviceCard(dev))
        }
    }

    private fun buildDeviceCard(dev: DeviceStore.SavedDevice): View {
        val isOnline = dev.id in onlineIds
        val hasSession = DeviceStore.hasSessionToken(this, dev.id)
        val card = ParentUi.card(this, p, paddingDp = 15)

        // 第一行：名称 + 状态徽章 + 菜单
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nameLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val prefix = if (dev.pinned) "★ " else ""
        val typeIcon = if (dev.isTv) "📺 " else "📱 "
        nameLine.addView(TextView(this).apply {
            text = "$prefix$typeIcon${dev.name}"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.textPrimary)
        })
        nameLine.addView(ParentUi.spacer(this, 1, 8))
        nameLine.addView(
            ParentUi.badge(
                this,
                if (isOnline) "在线" else "离线",
                if (isOnline) p.greenSoft else p.surfaceAlt,
                if (isOnline) p.green else p.textMuted
            )
        )
        if (hasSession) {
            nameLine.addView(ParentUi.spacer(this, 1, 6))
            nameLine.addView(ParentUi.badge(this, "已登录", p.accentSoft, p.accentText))
        }
        titleWrap.addView(nameLine)

        val metaParts = ArrayList<String>()
        metaParts.add(dev.host)
        if (dev.version.isNotBlank()) metaParts.add("v${dev.version}")
        if (dev.questions > 0) metaParts.add("${dev.questions} 题")
        titleWrap.addView(TextView(this).apply {
            text = metaParts.joinToString(" · ")
            textSize = 12.5f
            setTextColor(p.textMuted)
            setPadding(0, ParentUi.dp(this@ParentHomeActivity, 4), 0, 0)
        })
        topRow.addView(titleWrap)

        val moreBtn = TextView(this).apply {
            text = "⋯"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.textMuted)
            val ph = ParentUi.dp(this@ParentHomeActivity, 10)
            val pv = ParentUi.dp(this@ParentHomeActivity, 4)
            setPadding(ph, pv, ph, pv)
            isClickable = true
            isFocusable = true
            setOnClickListener { v -> showDeviceMenu(v, dev) }
        }
        topRow.addView(moreBtn)
        card.addView(topRow)

        card.addView(ParentUi.spacer(this, 12))

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        btnRow.addView(ParentUi.primaryButton(this, p, "进入后台") {
            openDeviceConsole(dev)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f)
        })
        btnRow.addView(ParentUi.spacer(this, 1, 8))
        btnRow.addView(ParentUi.secondaryButton(this, p, "修权限") {
            openAdbWizard(prefillIp = dev.ip, fixOnly = true)
        })
        btnRow.addView(ParentUi.spacer(this, 1, 8))
        btnRow.addView(ParentUi.secondaryButton(this, p, "复制网址") {
            ParentUi.copyToClipboard(this, "StudyOnTV Admin URL", dev.adminUrl, "已复制 ${dev.adminUrl}")
        })
        card.addView(btnRow)

        return card
    }

    private fun showDeviceMenu(anchor: View, dev: DeviceStore.SavedDevice) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, if (dev.pinned) "取消置顶" else "置顶")
        menu.menu.add(0, 2, 1, "刷新状态")
        menu.menu.add(0, 3, 2, "无线 ADB 重装 / 升级")
        if (DeviceStore.hasSessionToken(this, dev.id)) menu.menu.add(0, 5, 3, "清除登录状态")
        menu.menu.add(0, 4, 4, "移除")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    devices = DeviceStore.togglePin(this, dev.id)
                    renderDevices()
                }
                2 -> probeSingleHost(dev.baseUrl, openOnSuccess = false)
                3 -> openAdbWizard(prefillIp = dev.ip, fixOnly = false)
                5 -> {
                    DeviceStore.saveSessionToken(this, dev.id, "")
                    renderDevices()
                    Toast.makeText(this, "已清除登录状态", Toast.LENGTH_SHORT).show()
                }
                4 -> {
                    devices = DeviceStore.remove(this, dev.id)
                    renderDevices()
                }
            }
            true
        }
        menu.show()
    }

    private fun showManualAddDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = ParentUi.dp(this@ParentHomeActivity, 20)
            setPadding(pad, ParentUi.dp(this@ParentHomeActivity, 10), pad, 0)
        }
        val input = EditText(this).apply {
            hint = if (BuildConfig.LOCAL_ONLY) "例如 192.168.1.88（默认端口 8099）" else "IP、Tailscale 或域名，如 192.168.1.88 或 https://tv.example.com"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle("手动添加设备")
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("连接") { _, _ ->
                val raw = input.text?.toString().orEmpty().trim()
                val parsed = LanNet.parseManualHost(raw, LanNet.DEFAULT_HTTP_PORT)
                if (parsed == null) {
                    val tip = if (BuildConfig.LOCAL_ONLY) "仅本地版只支持局域网 IP（如 192.168.x.x）" else "请输入有效 IP 或域名（如 192.168.1.88、100.64.x.x 或 https://tv.example.com）"
                    Toast.makeText(this, tip, Toast.LENGTH_LONG).show()
                } else {
                    probeSingleHost(parsed.baseUrl, openOnSuccess = true)
                }
            }
            .show()
    }

    private fun probeSingleHost(rawHost: String, openOnSuccess: Boolean) {
        val parsed = LanNet.parseManualHost(rawHost, LanNet.DEFAULT_HTTP_PORT) ?: return
        Toast.makeText(this, "正在连接 ${parsed.host}…", Toast.LENGTH_SHORT).show()
        io.execute {
            val found = LanNet.probeHelloTarget(parsed, timeoutMs = 4000)
            runOnUiThread {
                if (found != null) {
                    devices = DeviceStore.recordDiscovered(this, listOf(found))
                    onlineIds = onlineIds + found.id
                    renderDevices()
                    Toast.makeText(this, "已连上「${found.name}」", Toast.LENGTH_SHORT).show()
                    if (openOnSuccess) {
                        devices.firstOrNull { it.id == found.id }?.let { openDeviceConsole(it) }
                    }
                } else {
                    Toast.makeText(
                        this,
                        "连不上 ${parsed.host}：请确认设备已打开「题了个题」且网络可达",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun openDeviceConsole(dev: DeviceStore.SavedDevice) {
        startActivity(
            Intent(this, DeviceConsoleActivity::class.java)
                .putExtra(DeviceConsoleActivity.EXTRA_DEVICE_ID, dev.id)
                .putExtra(DeviceConsoleActivity.EXTRA_DEVICE_NAME, dev.name)
                .putExtra(DeviceConsoleActivity.EXTRA_IP, dev.ip)
                .putExtra(DeviceConsoleActivity.EXTRA_PORT, dev.port)
                .putExtra(DeviceConsoleActivity.EXTRA_SCHEME, dev.scheme)
        )
    }

    private fun openAdbWizard(prefillIp: String?, fixOnly: Boolean) {
        startActivity(
            Intent(this, AdbWizardActivity::class.java)
                .putExtra(AdbWizardActivity.EXTRA_PREFILL_IP, prefillIp.orEmpty())
                .putExtra(AdbWizardActivity.EXTRA_FIX_ONLY, fixOnly)
        )
    }
}
