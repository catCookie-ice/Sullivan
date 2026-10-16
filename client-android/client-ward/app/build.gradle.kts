plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.sullivan.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sullivan.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // 高德定位 API Key。受护人端侧检测用不到高德，但 Manifest 里声明了该 meta-data，
        // 占位符不满足会让 manifest merger 直接失败。从 local.properties / gradle 属性读，缺省空串。
        manifestPlaceholders["AMAP_API_KEY"] =
            (project.findProperty("amapApiKey") as String?) ?: ""
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // 显式指定源码编码：gradle.properties 里把 daemon 的 file.encoding 设成了平台默认
        // （GBK，为的是让 Gradle 写出的 @argfile 能被 worker 正确解码），若不显式指定，
        // javac 会跟着用 GBK 读源码，中文字符串字面量（播报文案）就会编译成乱码。
        encoding = "UTF-8"
    }

    androidResources {
        // .onnx 模型直接以未压缩方式打包，避免每次加载时解压整个文件（12 MB）。
        noCompress.add("onnx")
    }

    buildFeatures {
        // ApiClient 用 BuildConfig.DEBUG 决定要不要装 HTTP 日志拦截器：
        // release 里绝不能把请求体（含密码、clientSecret）写进 logcat。
        buildConfig = true
    }
}

// 本模块是纯 Java 工程（零 .kt 文件），因此不挂 kotlin-android 插件。
// 原先挂着的 kotlin + kapt + Compose + Hilt 是一整套没有一行代码用到的死依赖；
// kapt 与中文路径、GBK 编码的组合又是这条工具链里最脆的一环，去掉它的收益是实打实的。
dependencies {
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.amap.location)

    // 受护人端侧障碍检测：相机预览 + 端侧 ONNX 推理
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.annotation)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.onnxruntime.android)

    // 二维码：生成（绑定码/登录码）与解码（扫码页共用同一套 BitMatrix 处理）
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}
