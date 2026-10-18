package com.sullivan.android.data.session;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.sullivan.android.BuildConfig;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 登录态的本地存储，以及后端地址的来源。
 *
 * <p>写法与 {@code vision/assist/AssistSettings} 保持一致：SharedPreferences、
 * 写入一律走 {@code apply()}（不在主线程等磁盘）。
 *
 * <p><b>令牌是明文存的</b>，没有用 EncryptedSharedPreferences：本工程连后端都还在
 * 明文 HTTP 上（见 {@code docs/final/17-开发日记-2026-10-16.md} 的已知风险），
 * 在网络层没上 TLS 之前，把令牌单独加密只能防住「拿到已 root 设备的人」这一类攻击者，
 * 挡不住同网段的嗅探者 —— 而后者才是这套扫码流程的现实威胁模型。
 * 加密留到后端上 TLS 时一并做，届时这个类的语义不变，换实现即可。
 *
 * <p><b>后端地址是构建期常量，不是用户设置。</b>取 {@code BuildConfig.SERVER_BASE_URL}，
 * 由 {@code app/build.gradle.kts} 从 {@code gradle.properties} 的
 * {@code serverBaseUrl} 属性注入（写法同 {@code amapApiKey}），打哪个包就连哪台服务器。
 *
 * <p>这个类此前把地址存进 SharedPreferences 并在两个页面提供输入框，已撤销。
 * 撤销的理由是它给出的是一个**假的选择权**：需要填地址的只有帮忙部署的那个人，
 * 而受护人端按既定口径不做账密登录、根本没人替它把地址填对；
 * 结果是真机首次安装必然撞上出厂默认值 {@code 10.0.2.2}（模拟器专用的宿主别名，
 * 真机上无此路由），受护人只看到「网络连接失败」，不知道该改哪里、也没权限改。
 * 把地址交回给打包的人，这类故障就变成构建期就能发现的问题，而不是现场故障。
 */
public final class SessionStore {

    private static final String FILE_NAME = "session";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_PRINCIPAL_ID = "principal_id";
    private static final String KEY_PRINCIPAL_TYPE = "principal_type";
    private static final String KEY_ROLES = "roles";
    private static final String KEY_ACCOUNT_LABEL = "account_label";

    private final SharedPreferences preferences;

    public SessionStore(@NonNull Context context) {
        this.preferences = context.getApplicationContext()
                .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 后端基地址，来自构建期常量，保证以 {@code /} 结尾
     * —— Retrofit 要求 baseUrl 以斜杠收尾。
     */
    @NonNull
    public String baseUrl() {
        String configured = BuildConfig.SERVER_BASE_URL;
        if (TextUtils.isEmpty(configured)) {
            // gradle.properties 里没配 serverBaseUrl 时会是空串。空串交给 Retrofit
            // 只会得到一句 "IllegalArgumentException: Invalid URL" 之类的报错，
            // 栈里看不出是「配置缺失」；在这里补一刀，把它直接说清楚。
            throw new IllegalStateException(
                    "未配置 serverBaseUrl：请在 gradle.properties（或 local.properties）"
                            + "里设置 serverBaseUrl=http://<主机>:8642/api/v1/");
        }
        return configured.endsWith("/") ? configured : configured + "/";
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
