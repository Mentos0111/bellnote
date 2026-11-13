plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ── 版本号 ───────────────────────────────────────────────────────────────────
// 升哪一位只看一件事：**用户装上之后要不要做额外动作**（不是看改了多少代码）。
//   不做任何事就能继续用 → 补丁（3.14.4）
//   有新功能但不做也能用旧的 → 次位（3.15）
//   必须迁移/重新授权/重新配置才能继续用 → 主位（4.0）
// 完整规程见 HANDOFF.md「发版规程」。
//
// 真相源是 git tag（v3.12 / v3.12.1）。CI 打 tag 时用 -PbellnoteVersionName=<tag 去掉 v>
// 传进来，包里的号就是那个 tag；本地直接构建没传，就用下面这个默认值，只为装到手机上能看号，
// 不代表发布版本。改这个默认值不影响发布——发布永远以 tag 为准。
// versionCode 由 versionName 推导：major*10000 + minor*100 + patch（3.12 → 31200），
// 单调递增、可读、能从号反推版本，不用人肉 +1。
val bellnoteDefaultVersionName = "3.17.4"

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

        // 只打 arm64。ML Kit 的 OCR native 库每个架构 7~12MB，四份就是 41MB；
        // 现在市面上的手机基本都 arm64，留一个能省 30MB。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
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

    // 离线 OCR：中文识别模型随包内置，不需要 GMS，也不联网
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // 离线大模型推理（MediaPipe LLM Inference）：模型文件不进包，首次用的时候下载。
    // 原生库只保留 arm64（见上面的 abiFilters），包体 +11MB 左右。
    implementation("com.google.mediapipe:tasks-genai:0.10.24")
}
