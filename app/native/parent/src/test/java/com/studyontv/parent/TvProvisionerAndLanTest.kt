package com.studyontv.parent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvProvisionerAndLanTest {

    @Test
    fun privateLanIpValidationRejectsPublicLoopbackAndOctal() {
        assertTrue(LanNet.isPrivateLanIp("10.0.0.1"))
        assertTrue(LanNet.isPrivateLanIp("172.16.0.1"))
        assertTrue(LanNet.isPrivateLanIp("172.31.255.254"))
        assertTrue(LanNet.isPrivateLanIp("192.168.31.85"))

        assertFalse(LanNet.isPrivateLanIp("8.8.8.8"))
        assertFalse(LanNet.isPrivateLanIp("127.0.0.1"))
        assertFalse(LanNet.isPrivateLanIp("169.254.1.10"))
        assertFalse(LanNet.isPrivateLanIp("172.15.0.1"))
        assertFalse(LanNet.isPrivateLanIp("172.32.0.1"))
        assertFalse(LanNet.isPrivateLanIp("192.168.01.1"))
        assertFalse(LanNet.isPrivateLanIp("192.168.1.256"))
        assertFalse(LanNet.isPrivateLanIp("example.com"))
    }

    @Test
    fun parsePrivateLanHostHandlesPrefixesPathsAndPorts() {
        assertEquals(LanNet.ParsedHost("192.168.31.85", 8099), LanNet.parsePrivateLanHost("192.168.31.85"))
        assertEquals(
            LanNet.ParsedHost("192.168.31.85", 5555),
            LanNet.parsePrivateLanHost("http://192.168.31.85:5555/admin/", 8099)
        )
        assertNull(LanNet.parsePrivateLanHost("192.168.31.85:80"))
        assertNull(LanNet.parsePrivateLanHost("8.8.8.8:8099"))
        assertNull(LanNet.parsePrivateLanHost("192.168.31.85:70000"))
    }

    @Test
    fun parsePeerPacketValidatesMagicAndPrivateSender() {
        val json = """{"magic":"SOTV_PEER_V1","id":"dev-1","name":"客厅电视","port":8099,"version":"0.5.9","isTv":true,"questions":42}"""
        val bytes = json.toByteArray(Charsets.UTF_8)

        val ok = LanNet.parsePeerPacket(bytes, bytes.size, "192.168.1.50", nowMs = 1000L)
        assertNotNull(ok)
        assertEquals("dev-1", ok!!.id)
        assertEquals("客厅电视", ok.name)
        assertEquals(42, ok.questions)

        assertNull(LanNet.parsePeerPacket(bytes, bytes.size, "8.8.8.8"))
        val badMagic = """{"magic":"OTHER","port":8099}""".toByteArray()
        assertNull(LanNet.parsePeerPacket(badMagic, badMagic.size, "192.168.1.50"))
    }

    @Test
    fun buildSubnetScanIpsExcludesSelfAndCovers253Hosts() {
        val ips = LanNet.buildSubnetScanIps(listOf("192.168.31.85"))
        assertEquals(253, ips.size)
        assertFalse(ips.contains("192.168.31.85"))
        assertTrue(ips.contains("192.168.31.1"))
        assertTrue(ips.contains("192.168.31.254"))
    }

    @Test
    fun deviceStoreMergeTracksIpChangeAndPreservesPin() {
        val existing = listOf(
            DeviceStore.SavedDevice(
                id = "tv-001",
                name = "客厅电视",
                ip = "192.168.31.10",
                port = 8099,
                version = "0.5.8",
                pinned = true,
                lastSeenAt = 100L
            ),
            DeviceStore.SavedDevice(
                id = "pad-002",
                name = "学习平板",
                ip = "192.168.31.20",
                port = 8099,
                version = "0.5.9",
                pinned = false,
                lastSeenAt = 500L
            )
        )
        val discovered = listOf(
            LanNet.DiscoveredDevice("tv-001", "客厅电视", "192.168.31.99", 8099, "0.5.9", true, 120, lastSeenAt = 300L)
        )

        val merged = DeviceStore.mergeDiscovered(existing, discovered)
        assertEquals(2, merged.size)
        // 置顶设备仍排第一，且 IP 自动更新为 192.168.31.99
        assertEquals("tv-001", merged[0].id)
        assertEquals("192.168.31.99", merged[0].ip)
        assertEquals("0.5.9", merged[0].version)
        assertTrue(merged[0].pinned)
        assertEquals("pad-002", merged[1].id)
    }

    @Test
    fun mergeAccessibilityServicesPreservesExistingAndDeduplicates() {
        val target = "com.studyontv.tv/com.studyontv.tv.WatchAccessibilityService"

        assertEquals(target, TvProvisioner.mergeAccessibilityServices(null, target))
        assertEquals(target, TvProvisioner.mergeAccessibilityServices("null\n", target))

        val withTalkBack = "com.google.android.marvin.talkback/.TalkBackService"
        assertEquals(
            "$withTalkBack:$target",
            TvProvisioner.mergeAccessibilityServices(withTalkBack, target)
        )

        // 已存在简写类名时不重复追加
        val alreadyShort = "$withTalkBack:com.studyontv.tv/.WatchAccessibilityService"
        assertEquals(alreadyShort, TvProvisioner.mergeAccessibilityServices(alreadyShort, target))
    }

    @Test
    fun detectIsTvAndResolveTargetPackage() {
        assertTrue(TvProvisioner.detectIsTv("tv,nosdcard", ""))
        assertTrue(TvProvisioner.detectIsTv("default", "feature:android.software.leanback"))
        assertFalse(TvProvisioner.detectIsTv("tablet", "feature:android.hardware.touchscreen"))

        val tvWithApp = TvProvisioner.DeviceInfo("Xiaomi", "MiTV", 28, isTv = true, tvInstalled = true, mobileInstalled = false)
        assertEquals(
            TvProvisioner.PKG_TV,
            TvProvisioner.resolveTargetPackage(TvProvisioner.Mode.FIX_PERMISSIONS_ONLY, null, tvWithApp)
        )
        val emptyTv = tvWithApp.copy(tvInstalled = false)
        assertNull(TvProvisioner.resolveTargetPackage(TvProvisioner.Mode.FIX_PERMISSIONS_ONLY, null, emptyTv))
        assertEquals(
            TvProvisioner.PKG_TV,
            TvProvisioner.resolveTargetPackage(TvProvisioner.Mode.INSTALL_AND_CONFIGURE, null, emptyTv)
        )
    }

    @Test
    fun parseInstallOutputClassifiesSuccessAndCommonFailures() {
        assertTrue(TvProvisioner.parseInstallOutput("Success\n").success)

        val cert = TvProvisioner.parseInstallOutput("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match]")
        assertFalse(cert.success)
        assertTrue(cert.message.contains("签名不一致"))

        val storage = TvProvisioner.parseInstallOutput("Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]")
        assertFalse(storage.success)
        assertTrue(storage.message.contains("存储空间不足"))

        val restricted = TvProvisioner.parseInstallOutput("Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]")
        assertFalse(restricted.success)
        assertTrue(restricted.message.contains("拦截"))
    }

    @Test
    fun buildPermissionPlanAdaptsToAndroidSdk() {
        val api21 = TvProvisioner.buildPermissionPlan(TvProvisioner.PKG_TV, 21, "a/b")
        assertFalse(api21.any { it.id == "perm_install" })
        assertFalse(api21.any { it.id == "perm_battery" })
        assertTrue(api21.any { it.command.contains("GET_USAGE_STATS") })

        val api29 = TvProvisioner.buildPermissionPlan(TvProvisioner.PKG_TV, 29, "a/b")
        assertTrue(api29.any { it.id == "perm_install" })
        assertTrue(api29.any { it.id == "perm_battery" })
        assertTrue(api29.any { it.command.contains("WRITE_SECURE_SETTINGS") })
        assertFalse(api29.any { it.id == "perm_notify" })

        val api33 = TvProvisioner.buildPermissionPlan(TvProvisioner.PKG_TV, 33, "a/b")
        assertTrue(api33.any { it.id == "perm_notify" && it.command.contains("POST_NOTIFICATIONS") })
    }

    @Test
    fun deviceStoreValidatesSessionTokenFormat() {
        assertTrue(DeviceStore.isValidSessionToken("a".repeat(64)))
        assertTrue(DeviceStore.isValidSessionToken("0123456789abcdef".repeat(4)))
        assertFalse(DeviceStore.isValidSessionToken(""))
        assertFalse(DeviceStore.isValidSessionToken("a".repeat(63)))
        assertFalse(DeviceStore.isValidSessionToken("g".repeat(64)))
        assertFalse(DeviceStore.isValidSessionToken("A".repeat(64)))
    }

    @Test
    fun resolveFileChooserMimeTypeNeverRestrictsDocumentOrJsonToNarrowMime() {
        assertEquals("*/*", DeviceConsoleActivity.resolveFileChooserMimeType(null))
        assertEquals("*/*", DeviceConsoleActivity.resolveFileChooserMimeType(emptyArray()))
        assertEquals(
            "*/*",
            DeviceConsoleActivity.resolveFileChooserMimeType(arrayOf(".json", ".docx", ".pdf", ".txt", "application/json"))
        )
        assertEquals(
            "*/*",
            DeviceConsoleActivity.resolveFileChooserMimeType(arrayOf(".json,.docx,.pdf,.txt,application/json"))
        )
        assertEquals("*/*", DeviceConsoleActivity.resolveFileChooserMimeType(arrayOf(".json", "application/json")))
        assertEquals("image/*", DeviceConsoleActivity.resolveFileChooserMimeType(arrayOf("image/*")))
        assertEquals("image/*", DeviceConsoleActivity.resolveFileChooserMimeType(arrayOf(".png", ".jpg", "image/webp")))
        assertEquals("audio/*", DeviceConsoleActivity.resolveFileChooserMimeType(arrayOf("audio/*")))
    }

    @Test
    fun parentDiagOssKeyRotationAndCrashRetryInvariants() {
        assertTrue(ParentDiag.isAliyunOssEndpoint("https://sotv-logs-bj.oss-cn-beijing.aliyuncs.com"))
        assertFalse(ParentDiag.isAliyunOssEndpoint("https://worker.example.com/upload"))
        assertFalse(ParentDiag.isAliyunOssEndpoint("not-a-url"))

        val key = ParentDiag.ossObjectKey("parent-crash", nowMillis = 1_764_300_000_000L)
        assertTrue(key.startsWith("logs/20251128/1764300000_"))
        assertTrue(key.contains("_parent-crash_"))
        assertTrue(key.endsWith(".zip"))

        val tmpDir = kotlin.io.path.createTempDirectory("parent-diag-test").toFile()
        try {
            val current = java.io.File(tmpDir, "parent.log")
            current.writeText("x".repeat(120))
            ParentDiag.rotateInDir(tmpDir, maxBytes = 100L, keep = 2)
            assertFalse(current.exists())
            assertTrue(java.io.File(tmpDir, "parent.log.1").exists())
        } finally {
            tmpDir.deleteRecursively()
        }

        var writtenBack = ""
        val failed = ParentDiag.runPendingCrashUpload(
            readAndClear = { "RuntimeException: boom" },
            writePending = { writtenBack = it },
            buildZip = { byteArrayOf(1, 2, 3) },
            upload = { 503 }
        )
        assertFalse(failed)
        assertEquals("RuntimeException: boom", writtenBack)

        writtenBack = ""
        val ok = ParentDiag.runPendingCrashUpload(
            readAndClear = { "RuntimeException: boom" },
            writePending = { writtenBack = it },
            buildZip = { byteArrayOf(1, 2, 3) },
            upload = { 200 }
        )
        assertTrue(ok)
        assertEquals("", writtenBack)
    }

    @Test
    fun parentUpdaterSemVerAndManifestPgyerPreference() {
        assertTrue(ParentUpdater.isNewer("0.5.15", "0.5.14"))
        assertTrue(ParentUpdater.isNewer("0.5.14", "0.5.14-beta"))
        assertFalse(ParentUpdater.isNewer("0.5.14", "0.5.14"))
        assertFalse(ParentUpdater.isNewer("0.5.13", "0.5.14"))

        val manifestWithPgyer = """
            {
              "version": "0.5.15",
              "versionCode": 62,
              "notes": "修复若干问题",
              "assets": {
                "parent": {
                  "url": "https://github.com/chadyi/StudyOnTV/releases/download/v0.5.15/StudyOnTV-Parent-v0.5.15.apk",
                  "pgyerUrl": "https://www.pgyer.com/StudyOnTVParent",
                  "pgyerKey": "abc123key"
                }
              }
            }
        """.trimIndent()
        val parsed = ParentUpdater.parseManifestUpdate(manifestWithPgyer, "0.5.14", 61)
        assertNotNull(parsed)
        assertEquals("0.5.15", parsed!!.version)
        assertEquals("https://www.pgyer.com/StudyOnTVParent", parsed.pageUrl)
        assertTrue(parsed.isPgyerPage)
        assertEquals("abc123key", parsed.pgyerKey)

        // 当前已是同版本或更新版本时返回 null
        assertNull(ParentUpdater.parseManifestUpdate(manifestWithPgyer, "0.5.15", 62))
        assertNull(ParentUpdater.parseManifestUpdate(manifestWithPgyer, "0.5.16", 63))

        // 无蒲公英链接时回退到 GitHub APK 直链，再回退到 Releases 页
        assertEquals(
            "https://github.com/chadyi/StudyOnTV/releases/download/v0.5.15/StudyOnTV-Parent-v0.5.15.apk",
            ParentUpdater.resolveDownloadUrl("", "https://github.com/chadyi/StudyOnTV/releases/download/v0.5.15/StudyOnTV-Parent-v0.5.15.apk")
        )
        assertEquals(
            ParentUpdater.RELEASES_FALLBACK_URL,
            ParentUpdater.resolveDownloadUrl("http://insecure.example", "")
        )
    }
}
