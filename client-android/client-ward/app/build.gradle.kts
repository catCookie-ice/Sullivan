import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    kotlin("kapt")
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
}

// 不显式指定的话 Kotlin 默认 jvmTarget 1.8，与上面的 Java 17 冲突，
// 构建报 Inconsistent JVM-target compatibility。
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.navigation)
    implementation(libs.lifecycle.viewmodel.compose)

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

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

    testImplementation(libs.junit)
}

kapt {
    correctErrorTypes = true
}
