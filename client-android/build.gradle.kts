// Sullivan Android 客户端 — 受护人/监护人/志愿者
// docs/final/13 §2: Kotlin 2.0 + Jetpack Compose + Hilt
// minSdk 26 (Android 8), targetSdk 35
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.hilt.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
