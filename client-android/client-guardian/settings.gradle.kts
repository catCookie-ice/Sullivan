pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // 高德 SDK 不在 google()/mavenCentral()，官方发布通道即阿里云 public 仓库。
        maven("https://maven.aliyun.com/repository/public")
    }
}

// 目录名曾叫 client-ward（ward = 受护人），受护人端拆出去后那个名字会误导，
// 因此改为 client-guardian —— rootProject.name 跟着一起改。
// 注意它与 applicationId / namespace 无关：那两项是 com.sullivan.android，未变。
rootProject.name = "SullivanGuardian"
include(":app")
