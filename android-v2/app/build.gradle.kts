import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    // 2026-09-17 效率改造：kotlin-kapt → KSP。
    // 动因：kapt 走 javac 注解处理，每次都要为全模块重生成 stub，
    // 一生成就把 Kotlin 的增量编译作废 → 改 1 行 Kotlin 触发整模块全量重编（~2min）。
    // KSP 是 Kotlin 原生符号处理（不生成 stub），增量编译得以保留。
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.retaildemo.v2"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.retaildemo.v2"
        minSdk = 26
        targetSdk = 34
        // versionCode 用日期编码（YYYYMMDD+序），天然单调递增——2026-09-10 起
        // 店内手机出现过高 versionCode 历史测试包导致系统拒装降级包（推送更新失效），日期编码一劳永逸
        versionCode = 2026102204
        versionName = "2.22.4"
        vectorDrawables.useSupportLibrary = true
        // 网关 token 由 gradle.properties 构建期注入（默认空），零硬编码。
        val gatewayToken = providers.gradleProperty("GATEWAY_TOKEN").getOrElse("")
        buildConfigField("String", "GATEWAY_TOKEN", "\"${gatewayToken.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                test.systemProperty("http.proxyHost", "127.0.0.1")
                test.systemProperty("http.proxyPort", "7892")
                test.systemProperty("https.proxyHost", "127.0.0.1")
                test.systemProperty("https.proxyPort", "7892")
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("com.google.dagger:hilt-android:2.50")
    ksp("com.google.dagger:hilt-android-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")
    implementation("androidx.hilt:hilt-work:1.1.0")
    ksp("androidx.hilt:hilt-compiler:1.1.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0") // L10：老王流式对话（官方 SSE 客户端，不手搓）
    // L10：老王回答 Markdown 渲染（Apache-2.0，专为 LLM 流式场景：增量只重解析尾部）
    // 锁 0.25.0：0.26+ 起为 Kotlin 2.0.20+ 元数据、0.28.0 起按 Compose 1.7 编译（调用
    // Composer.startReplaceGroup，工程 Compose 1.6.8 无此方法 → 真机渲染即 NoSuchMethodError 闪退，
    // 2026-09-11 真机复现实锤）；0.25.0 字节码核验无 startReplaceGroup、API 与 0.28.0 一致
    implementation("com.mikepenz:multiplatform-markdown-renderer:0.25.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.25.0")
    implementation("com.google.code.gson:gson:2.10.1")

    implementation("androidx.work:work-runtime-ktx:2.9.0")

    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.2.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0") // L10：SSE 客户端单测喂流
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("io.mockk:mockk-agent:1.13.13")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("androidx.work:work-testing:2.9.0")
    testImplementation("com.google.dagger:hilt-android-testing:2.50")
}

// 排雷 ③a（2026-09-10）：Room schema 导出到 app/schemas/，随版本入库，
// Migration 与 Entity 列定义漂移在编译期可见
// （2026-09-17 随 kapt→KSP 迁移，由 kapt { arguments } 改为 ksp { arg }；
//   该 arg 对全部 KSP 任务生效，含 test 源集）
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

