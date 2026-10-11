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

rootProject.name = "SullivanAndroid"
include(":app")
