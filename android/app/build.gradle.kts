plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.xmu.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xmu.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.1.4"
        buildConfigField("boolean", "NETWORK_METRICS", "false")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("META-INF/NOTICE.md", "META-INF/LICENSE.md")
        }
    }

    testOptions {
        unitTests {
            // Robolectric 运行 Compose UI 测试需要 Android 资源
            isIncludeAndroidResources = true
        }
    }

    signingConfigs {
        // 固定调试签名：keystore 固定到项目内，保证覆盖安装（install -r）不丢本地登录数据
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        create("networkBenchmark") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            buildConfigField("boolean", "NETWORK_METRICS", "true")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            // 沿用现有固定签名：保证已安装用户覆盖升级（install -r）不丢加密登录数据
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.android.mail)
    implementation(libs.android.activation)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.mockwebserver)
    // Compose UI 测试（Robolectric 在 JVM 上渲染，无需模拟器）
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    // Robolectric 的 Compose UI 测试（ScheduleWeekGridUiTest/ThemeUiTest)也依赖此 debug manifest
    // 提供可解析的 ComponentActivity，不能删
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
