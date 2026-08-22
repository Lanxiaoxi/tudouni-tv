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
        // Android 8.0+，覆盖绝大多数国产盒子
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        getByName("debug") {
            // 复用 debug 签名给 release 用，免去创建 keystore
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
