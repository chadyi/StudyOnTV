import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) props.load(f.inputStream())
}

android {
    namespace = "com.studyontv.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.studyontv.tv"
        minSdk = 21          // Android 5.0
        targetSdk = 34
        testInstrumentationRunner = "com.studyontv.tv.RegressionInstrumentation"
        versionCode = 40
        versionName = "0.3.35"
        // 带电视桌面的包在 desktop 这个 buildType 里改成 true
        buildConfigField("boolean", "DESKTOP", "false")
    }

    flavorDimensions += "device"
    productFlavors {
        create("tv") {
            dimension = "device"
            applicationId = "com.studyontv.tv"
        }
        create("mobile") {
            dimension = "device"
            applicationId = "com.studyontv.mobile"
        }
    }

    signingConfigs {
        create("release") {
            val ks = localProps.getProperty("KEYSTORE_PATH", "")
            if (ks.isNotEmpty()) {
                storeFile = file(ks)
                storePassword = localProps.getProperty("KEYSTORE_PASS")
                keyAlias = localProps.getProperty("KEY_ALIAS")
                keyPassword = localProps.getProperty("KEY_PASS")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            val cfg = signingConfigs.getByName("release")
            if (cfg.storeFile != null) signingConfig = cfg
        }
        // 带电视桌面的包：默认发的包不声明 HOME（有的电视商店因此拒装），
        // 需要桌面的人从 GitHub Release 下这个包。源码在 src/desktop/。
        // 包名、版本号和普通包一致，两个包可以互相覆盖安装，家长数据不会丢。
        create("desktop") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            // 内测包：版本号和图标名都带标记，家长一眼能和默认包区分；
            // 版本号带 -beta 后，蒲公英上的正式版也不会把内测包判成「有新版本」
            versionNameSuffix = "-beta"
            buildConfigField("boolean", "DESKTOP", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    lint {
        abortOnError = false
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // Bugly 崩溃上报（用户同意后才初始化，见 BuglyInit.kt）
    implementation(files("libs/bugly-crashreport.aar"))
}

// 把网页和题库同步进 assets（仓库里只维护 app/web、admin、data/seed.json 三份源）
val repoRoot = rootProject.projectDir.parentFile.parentFile
val syncWebAssets by tasks.registering(Sync::class) {
    from(File(repoRoot, "app/web")) { into("web") }
    from(File(repoRoot, "admin")) { into("admin") }
    from(File(repoRoot, "data/seed.json")) { rename { "questions.json" } }
    from(File(repoRoot, "shared/katex")) { into("web/assets/vendor/katex") }
    from(File(repoRoot, "shared/math.js")) { into("web/assets/js") }
    from(File(repoRoot, "data/holidays")) { into("holidays") }
    into(layout.projectDirectory.dir("src/main/assets"))
}

tasks.named("preBuild") { dependsOn(syncWebAssets) }
