package com.sullivan.android.data.session;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 登录态与服务器地址的本地存储。
 *
 * <p>写法与 {@code vision/assist/AssistSettings} 保持一致：SharedPreferences、
 * 写入一律走 {@code apply()}（不在主线程等磁盘）。
 *
 * <p><b>令牌是明文存的</b>，没有用 EncryptedSharedPreferences：本工程连后端都还在
 * 明文 HTTP 上（见 {@code docs/final/17-开发日记-2026-10-16.md} 的已知风险），
 * 在网络层没上 TLS 之前，把令牌单独加密只能防住「拿到已 root 设备的人」这一类攻击者，
 * 挡不住同网段的嗅探者 —— 而后者才是这套扫码流程的现实威胁模型。
 * 加密留到后端上 TLS 时一并做，届时这个类的语义不变，换实现即可。
 */
public final class SessionStore {

    private static final String FILE_NAME = "session";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_PRINCIPAL_ID = "principal_id";
    private static final String KEY_PRINCIPAL_TYPE = "principal_type";
    private static final String KEY_ROLES = "roles";
    private static final String KEY_ACCOUNT_LABEL = "account_label";

    /**
     * 默认后端地址。
     *
     * <p>{@code 10.0.2.2} 是 Android 模拟器里指向宿主机的固定地址，服务端默认监听 8642
     * （见 {@code server-java/README.md}）。真机联调时必须改成宿主机的局域网 IP，
     * 且手机与电脑在同一网段 —— 所以在设置里留了改动入口，不写死。
     */
    public static final String DEFAULT_BASE_URL = "http://10.0.2.2:8642/api/v1/";

    private final SharedPreferences preferences;

    public SessionStore(@NonNull Context context) {
        this.preferences = context.getApplicationContext()
                .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }

    /** 后端基地址，保证以 {@code /} 结尾 —— Retrofit 要求 baseUrl 以斜杠收尾。 */
    @NonNull
    public String baseUrl() {
        String stored = preferences.getString(KEY_BASE_URL, null);
        if (TextUtils.isEmpty(stored)) {
            return DEFAULT_BASE_URL;
        }
        return stored.endsWith("/") ? stored : stored + "/";
    }

    public void setBaseUrl(@NonNull String baseUrl) {
        preferences.edit().putString(KEY_BASE_URL, baseUrl.trim()).apply();
    }

    @Nullable
    public String accessToken() {
        return preferences.getString(KEY_ACCESS_TOKEN, null);
    }

    @Nullable
    public String principalId() {
        return preferences.getString(KEY_PRINCIPAL_ID, null);
    }

    /** 登录时用户输入的登录名。仅用于界面上「当前账号」的显示。 */
    @Nullable
    public String accountLabel() {
        return preferences.getString(KEY_ACCOUNT_LABEL, null);
    }

    @NonNull
    public Set<String> roles() {
        Set<String> stored = preferences.getStringSet(KEY_ROLES, null);
        return stored == null ? Collections.emptySet() : Collections.unmodifiableSet(stored);
    }

    public boolean isLoggedIn() {
        return !TextUtils.isEmpty(accessToken());
    }

    /** 登录成功（无论账密还是扫码兑换）后统一落库。 */
    public void saveLogin(@NonNull String accessToken, @Nullable String principalId,
                          @Nullable String principalType, @NonNull Set<String> roles,
                          @Nullable String accountLabel) {
        // 存副本：SharedPreferences 拿到的 StringSet 是内部实例，直接存回去会被文档明确警告。
        preferences.edit()
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putString(KEY_PRINCIPAL_ID, principalId)
                .putString(KEY_PRINCIPAL_TYPE, principalType)
                .putStringSet(KEY_ROLES, new HashSet<>(roles))
                .putString(KEY_ACCOUNT_LABEL, accountLabel)
                .apply();
    }

    /**
     * 退出登录。**不清 baseUrl**：服务器地址是设备配置，不是会话的一部分，
     * 退出后还要用它来重新登录。
     */
    public void clearLogin() {
        preferences.edit()
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_PRINCIPAL_ID)
                .remove(KEY_PRINCIPAL_TYPE)
                .remove(KEY_ROLES)
                .remove(KEY_ACCOUNT_LABEL)
                .apply();
    }
}
