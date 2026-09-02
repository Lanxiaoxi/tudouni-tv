plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tudouni.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tudouni.tv"
        // Android 5.0+：向下兼容老电视盒子（运营商定制/国产盒子大量停留在 5.1/7.1）。
        // 依赖最低要求：Compose BOM 2024.10.01 = 21、Media3 1.5.0 = 21、Coil 2.7.0 = 21、
        // DataStore 1.1.1 = 19 —— 21 是安全下限，再降会编译失败。
        minSdk = 21
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.1"
    }

    signingConfigs {
        getByName("debug") {
            // 复用 debug 签名给 release 用，免去创建 keystore
            // v1 (JAR) 必须开：Android 7.0 以下只认 v1；部分国产 ROM 的安装器
            // 即便在 8.0+ 上也只实现 v1 校验，缺 v1 会直接报「解析包错误」。
            enableV1Signing = true
            enableV2Signing = true
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        compose = true
        // SettingsScreen 展示版本号（BuildConfig.VERSION_NAME）
        buildConfig = true
    }

    lint {
        // Media3 1.5 把大量常用 API（PlayerView.showController、ExoPlayer.Builder 等）
        // 标为 @UnstableApi，且 opt-in 要求会沿调用链向上传播——逐个标注会把注解污染到整个
        // UI 层签名。该检查仅表示 API 可能在小版本间变动，并非运行时崩溃风险，
        // 故降为 warning：保留提示，但不阻断构建。
        warning += "UnsafeOptInUsageError"
    }
}

dependencies {
    // ---- Compose（BOM 统一版本）----
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // ---- 协程（显式声明，业务代码直接使用 launch/Dispatchers.IO/flow）----
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ---- 播放：Media3 ExoPlayer（HLS 扩展必须）----
    implementation("androidx.media3:media3-exoplayer:1.5.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.0")
    implementation("androidx.media3:media3-ui:1.5.0")

    // ---- 网络：Retrofit + Gson，对接现有 FastAPI 后端 ----
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // ---- 图片：Coil（加载封面，兼容 /covers/ 本地封面路径）----
    implementation("io.coil-kt:coil-compose:2.7.0")

    // ---- 持久化：DataStore（服务器地址 / token / 用户名）----
    implementation("androidx.datastore:datastore-preferences:1.1.1")
}
