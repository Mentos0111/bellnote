plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bellnote"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bellnote"
        minSdk = 26
        targetSdk = 34
        versionCode = 35
        versionName = "3.4"
    }

    signingConfigs {
        // 固定用仓库里这份 keystore（就是标准 Android debug key），保证本地构建与
        // GitHub Actions 产出的 APK 签名一致，已安装的用户可以直接覆盖升级。
        getByName("debug") {
            val keyStoreFile = rootProject.file("keystore/bellnote-debug.keystore")
            if (keyStoreFile.exists()) {
                storeFile = keyStoreFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 鐢?debug 绛惧悕锛屼究浜庡懡浠よ鐩存帴瀹夎鍒拌嚜宸辩殑鎵嬫満
            signingConfig = signingConfigs.getByName("debug")
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
