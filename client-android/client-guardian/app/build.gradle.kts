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
    // 绑定 / 活动 / 申请 / 会话四个列表共用一套适配器（SimpleListAdapter）。
    implementation(libs.androidx.recyclerview)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.onnxruntime.android)

    // 二维码：生成（绑定码/登录码）与解码（扫码页共用同一套 BitMatrix 处理）
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}
