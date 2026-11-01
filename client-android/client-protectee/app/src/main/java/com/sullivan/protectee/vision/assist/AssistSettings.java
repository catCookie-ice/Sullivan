package com.sullivan.protectee.vision.assist;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/**
 * 用户的设置：播报频率档位与两个播报开关。
 *
 * <p><b>为什么开关也要存</b>：改造前那两个开关就是 {@link PromptAnnouncer} 里的两个字段，
 * 重启即复原。用户把它关掉、下次打开又自己开了，只会得出"这开关是坏的"这一个结论。
 * 频率与开关属于同一件事——设置——所以放在一起，用同一个文件。
 *
 * <p>读到的档位名字认不出来时退回 {@link AnnounceRate#STANDARD}（见 {@link
 * AnnounceRate#fromName}）：换版本、装回旧版、存档被改坏都可能走到那条路，而 {@code valueOf}
 * 抛出的异常会在启动时把应用直接打掉。
 *
 * <p>写入一律用 {@code apply()}（异步落盘）：{@code commit()} 会在主线程上等磁盘，
 * 而这里每次点击都要写。
 */
public final class AssistSettings {

    private static final String FILE_NAME = "assist_settings";
    /** 存**名字**而不是序号：以后往中间插一档，序号会整体错位，名字不会。 */
    private static final String KEY_RATE = "announce_rate";
    /**
     * 语速档位。
     *
     * <p>**没有存档时默认 {@link SpeechPace#STANDARD}，它比平台默认快 15%** —— 这是
     * 用户明确要的改动（"加快障碍播报的语速"），不是可选项的副作用。旧版本升级上来的用户
     * 在这一项上会立刻听出差别，这正是预期的。
     */
    private static final String KEY_PACE = "speech_pace";
    private static final String KEY_SPEECH = "speech_enabled";
    private static final String KEY_HAPTICS = "haptics_enabled";
    /**
     * 报警后是否自动向监护人依次发起会话。
     *
     * <p>本地这份只是**镜像**：真正的口径以服务端 {@code /me/alert-setting} 为准。
     * 存本地是为了让界面在离线时也能正确显示开关状态，而不是每次进页面都闪一下默认值。
     * 写入时先改本地再推服务端；服务端失败时本地保留用户的选择，下次同步再补。
     */
    private static final String KEY_ALERT_AUTO_SESSION = "alert_auto_session";

    private final SharedPreferences preferences;

    public AssistSettings(@NonNull Context context) {
        this.preferences = context.getApplicationContext()
                .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }

    /** 用户选的播报频率档位；没设置过就是「标准」，即改造前的行为。 */
    @NonNull
    public AnnounceRate rate() {
        return AnnounceRate.fromName(preferences.getString(KEY_RATE, null), AnnounceRate.STANDARD);
    }

    public void setRate(@NonNull AnnounceRate rate) {
        preferences.edit().putString(KEY_RATE, rate.name()).apply();
    }

    /** 用户选的语速档位；没设置过就是「标准」（已比平台默认快 15%）。 */
    @NonNull
    public SpeechPace pace() {
        return SpeechPace.fromName(preferences.getString(KEY_PACE, null), SpeechPace.STANDARD);
    }

    public void setPace(@NonNull SpeechPace pace) {
        preferences.edit().putString(KEY_PACE, pace.name()).apply();
    }

    public boolean isSpeechEnabled() {
        return preferences.getBoolean(KEY_SPEECH, true);
    }

    public void setSpeechEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_SPEECH, enabled).apply();
    }

    public boolean isHapticsEnabled() {
        return preferences.getBoolean(KEY_HAPTICS, true);
    }

    public void setHapticsEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_HAPTICS, enabled).apply();
    }

    /**
     * 报警后是否自动呼叫监护人。**默认开启** —— 这是产品的默认行为，
     * 新用户不必先找到开关才算「有守护」。
     */
    public boolean isAlertAutoSessionEnabled() {
        return preferences.getBoolean(KEY_ALERT_AUTO_SESSION, true);
    }

    public void setAlertAutoSessionEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_ALERT_AUTO_SESSION, enabled).apply();
    }
}
