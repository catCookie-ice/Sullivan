import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

/**
 * 后端基地址。**构建期常量，不做成用户设置**（理由见 SessionStore 的类注释）。
 *
 * 取值顺序（先命中者胜）：
 *   1. Gradle 项目属性 —— `gradle.properties` 或命令行 `-PserverBaseUrl=…`；
 *   2. `local.properties`（**不入库**，见根 .gitignore）—— 本机的真实地址放这里。
 *
 * 分两处是有意的：`gradle.properties` 是入库文件，里面**不能出现真实内网 IP**
 * （地址与网段属于环境信息，不该进版本库）；而每个开发机的地址又各不相同，
 * 所以真实值放 `local.properties`，仓库里只留一个注释掉的占位示例。
 *
 * 本工程**没有**自己的地址，它与 client-guardian 连的是同一台服务器；
 * 但 `local.properties` 必须各有一份 —— 它是工程级文件，不会跨工程共享。
 */
// local.properties 的内容（Gradle 不会自动把它读成项目属性，得自己读）。
// 必须 import java.util.Properties：脚本作用域里 `java` 是 AGP 挂上的 JavaPluginExtension
// 访问器，写 java.util.Properties() 会被解析成「取 java 扩展的 util 属性」而编译失败。
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { stream -> load(stream) }
    }
}

val serverBaseUrl: String = (project.findProperty("serverBaseUrl") as String?)
    ?: localProperties.getProperty("serverBaseUrl")
    ?: ""

android {
    namespace = "com.sullivan.protectee"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sullivan.protectee"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.0"

        // 后端基地址（SessionStore 读 BuildConfig.SERVER_BASE_URL）。
        // 缺省是**空串而不是模拟器地址**：给一个看似可用的默认值（10.0.2.2）
        // 只会把「这个包到底连哪台服务器」这个必须回答的问题推迟到现场，
        // 而现场给出的答案往往是「网络连接失败」。空串会让第一次取地址时
        // 直接抛异常说清楚缺了什么（见 SessionStore.baseUrl()）。
        buildConfigField("String", "SERVER_BASE_URL", "\"$serverBaseUrl\"")
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
dependencies {
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.annotation)
    // 绑定 / 活动 / 会话三个列表共用一套适配器（SimpleListAdapter）。
    implementation(libs.androidx.recyclerview)

    // 端侧障碍检测：相机预览 + 端侧 ONNX 推理
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.onnxruntime.android)

    // 二维码：只用于生成（绑定码 / 登录票据），本端不扫。
    // WebRTC：语音/视频通话与监护人查看受护人摄像头的媒体通道。
    // 信令走既有的 WebSocket（RealtimeSocket），媒体走这里。
    implementation(libs.webrtc)

    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}
