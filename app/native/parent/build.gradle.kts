import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
}

val appGradleText = rootProject.file("app/build.gradle.kts").takeIf { it.exists() }?.readText().orEmpty()
val syncedVersionCode = Regex("""versionCode\s*=\s*(\d+)""").find(appGradleText)?.groupValues?.get(1)?.toIntOrNull() ?: 56
val syncedVersionName = Regex("""versionName\s*=\s*"([^"]+)"""").find(appGradleText)?.groupValues?.get(1) ?: "0.5.9"

android {
    namespace = "com.studyontv.parent"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.studyontv.parent"
        minSdk = 21
        targetSdk = 34
        versionCode = syncedVersionCode
        versionName = syncedVersionName
        val localOnly = (project.findProperty("localOnly")?.toString()?.toBoolean() == true) ||
            (System.getenv("LOCAL_ONLY")?.equals("true", ignoreCase = true) == true)
        buildConfigField("boolean", "LOCAL_ONLY", localOnly.toString())
        val crashEndpoint = if (localOnly) "" else (localProps.getProperty("CRASH_ENDPOINT") ?: System.getenv("CRASH_ENDPOINT") ?: "")
        val crashToken = if (localOnly) "" else (localProps.getProperty("CRASH_TOKEN") ?: System.getenv("CRASH_TOKEN") ?: "")
        buildConfigField("String", "CRASH_ENDPOINT", "\"$crashEndpoint\"")
        buildConfigField("String", "CRASH_TOKEN", "\"$crashToken\"")
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
