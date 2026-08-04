package com.sullivan.android.service.tts;

/**
 * 本地 TTS 播报服务 (FR-09)
 *
 * - 使用 Android 系统 TextToSpeech
 * - 可配置语速、音量、是否播报安全提示
 * - 高风险可中断低风险
 * - 停止导航清空非报警播报队列
 * - TTS 不可用时: 震动 + 高对比文字降级
 */
public class TTSService {
    // TODO(M3): TextToSpeech 初始化与播报
}
