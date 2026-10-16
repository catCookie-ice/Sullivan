// Sullivan Android 客户端 — 受护人/监护人/志愿者
// minSdk 26 (Android 8), targetSdk 35
//
// 技术栈：纯 Java + View/XML（本模块零 .kt 文件）。
// 这与 docs/final/13 §2 表格里写的「Kotlin + Jetpack Compose + Hilt」不一致，
// 该偏离及理由记录在 docs/final/17-开发日记-2026-10-16.md，未改动 13 号文档正文。
plugins {
    alias(libs.plugins.android.application) apply false
}
