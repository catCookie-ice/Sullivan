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
        // 本端**不用高德**：受护人手机上只有端侧检测与账号相关页面，
        // 没有地图、没有定位。因此这里也刻意不挂 aliyun 仓库（client-guardian 是为高德
        // SDK 才加的）；将来若真需要再加回来，不要以为这是漏抄。
    }
}

rootProject.name = "SullivanProtectee"
include(":app")
