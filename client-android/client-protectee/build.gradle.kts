// Sullivan 守护（受护人端）— 端侧障碍检测 + 账号相关页面
// minSdk 26 (Android 8), targetSdk 35
//
// 本工程由 client-guardian（原 client-ward）拆分而来：受护人端要装到一台专用设备上、
// 离线可用、带相机与端侧推理，而监护人/志愿者端是需要联网的账号应用。
// 两者混在一个 APK 里的代价是权限、入口、图标都不对，且监护人端为 12.8 MB 模型与
// ONNX Runtime 白买单。拆分过程与取舍见 docs/final/17-开发日记-2026-10-18-受护人端拆分.md。
//
// 技术栈：纯 Java + View/XML（本模块零 .kt 文件）。
// 与 docs/final/13 §2 表格里写的「Kotlin + Jetpack Compose + Hilt」不一致，
// 该偏离及理由记录在 docs/final/17-开发日记-2026-10-16.md。
plugins {
    alias(libs.plugins.android.application) apply false
}
