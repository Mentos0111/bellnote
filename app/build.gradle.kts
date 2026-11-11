plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ── 版本号 ───────────────────────────────────────────────────────────────────
// 真相源是 git tag（v3.12 / v3.12.1）。CI 打 tag 时用 -PbellnoteVersionName=<tag 去掉 v>
// 传进来，包里的号就是那个 tag；本地直接构建没传，就用下面这个默认值，只为装到手机上能看号，
// 不代表发布版本。改这个默认值不影响发布——发布永远以 tag 为准。
// versionCode 由 versionName 推导：major*10000 + minor*100 + patch（3.12 → 31200），
// 单调递增、可读、能从号反推版本，不用人肉 +1。
val bellnoteDefaultVersionName = "3.14.1"

fun bellnoteNormalizeVersion(raw: String): String {
    val cleaned = raw.trim().removePrefix("v")
    require(Regex("""^\d+\.\d+(\.\d+)?$""").matches(cleaned)) {
        "版本号必须是 X.Y 或 X.Y.Z（可带 v 前缀），收到：$raw"
    }
    return cleaned
}

fun bellnoteVersionCode(versionName: String): Int {
    val parts = versionName.split(".").map { it.toInt() }
    return parts[0] * 10_000 + parts.getOrElse(1) { 0 } * 100 + parts.getOrElse(2) { 0 }
}

val bellnoteVersionName = bellnoteNormalizeVersion(
    providers.gradleProperty("bellnoteVersionName").orNull ?: bellnoteDefaultVersionName
)

// 正式签名：优先读环境变量 BELLNOTE_KEYSTORE（CI 里从 secrets 解出来的 jks 路径）。
// 没配就退回仓库里的 debug key——本地能装、能验，但不能当正式发布用。
val bellnoteReleaseKeyStore = System.getenv("BELLNOTE_KEYSTORE")
    ?.takeIf { it.isNotBlank() }
    ?.let { file(it) }
    ?.takeIf { it.exists() }
val bellnoteBuildingRelease = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }

android {
    namespace = "com.bellnote"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bellnote"
        minSdk = 26
        targetSdk = 34
        versionCode = bellnoteVersionCode(bellnoteVersionName)
        versionName = bellnoteVersionName
    }

    signingConfigs {
        // 固定用仓库里这份 keystore（就是标准 Android debug key），保证本地构建与
        // GitHub Actions 产出的 APK 签名一致，已安装的用户可以直接覆盖升级。
        // 只在「没配正式签名」时兜底用；正式发布请配 BELLNOTE_KEYSTORE（见 HANDOFF 发版规程）。
        getByName("debug") {
            val keyStoreFile = rootProject.file("keystore/bellnote-debug.keystore")
            if (keyStoreFile.exists()) {
                storeFile = keyStoreFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        if (bellnoteReleaseKeyStore != null) {
            create("release") {
                storeFile = bellnoteReleaseKeyStore
                storePassword = System.getenv("BELLNOTE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BELLNOTE_KEY_ALIAS") ?: "bellnote"
                keyPassword = System.getenv("BELLNOTE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8：删未用代码 + 删未用资源。manifest 里声明的四大组件 R8 会自动保留；
            // 全项目没有反射（零 Class.forName），所以只需要少量 keep 规则。
            // 本地救数据用：-PbellnoteDebuggable=true 出一个可 run-as 的正式签名包，
            // 恢复完数据再用正常 release 包覆盖安装（同签名，数据保留）。
            isDebuggable = (project.findProperty("bellnoteDebuggable") == "true")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 正式签名优先；没配置时退回 debug key，保证本地仍可直接装机验证。
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug").also {
                    if (bellnoteBuildingRelease) {
                        logger.warn(
                            "bellnote: 未设置 BELLNOTE_KEYSTORE，release 包退回 debug key 签名。" +
                                "本地验证可以，正式发布请配置正式 keystore。"
                        )
                    }
                }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = false
    }

    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/DEPENDENCIES")
    }

    testOptions {
        unitTests.isReturnDefaultValues = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
