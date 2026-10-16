package com.sullivan.android.qr;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 二维码里承载的内容。纯逻辑、无 Android 依赖，可直接单测。
 *
 * <p>两条约定：
 * <ul>
 *   <li>绑定码：{@code sullivan://bind?code=K7M2XQ9TB4ND}</li>
 *   <li>登录票据：{@code sullivan://login?ticket=N7thzczK14lQ2Tixo9LCpw}</li>
 * </ul>
 *
 * <p><b>payload 里只有不透明的随机串</b>，不含姓名、账号、角色等任何身份信息 ——
 * 二维码会被旁人看见、会被拍照，而 {@code code}/{@code ticket} 本身不含信息，
 * 泄露的后果仅限于「对方可以去扫一次」（且受 TTL 与服务端授权校验约束）。
 *
 * <p>用自定义 scheme 而不是 https 链接：这是 App 对 App 的场景，
 * 没有落地页可打开，也不需要别人用浏览器扫出来看到一段看不懂的网页。
 *
 * <p><b>同时兼容裸码</b>（直接是那串字符、没有前缀）：扫码页必须留「手动输入」
 * 这条兜底路径（受护人屏幕坏了、监护人摄像头脏了），而手输的就是裸码。
 */
public final class QrPayload {

    public static final String SCHEME = "sullivan://";
    private static final String BIND_PREFIX = SCHEME + "bind?code=";
    private static final String LOGIN_PREFIX = SCHEME + "login?ticket=";

    /**
     * 绑定码字母表，与服务端 {@code common/RandomCode.java} 的 {@code DICTATABLE_ALPHABET}
     * 必须逐字一致：去掉了 0/O、1/I/L/U 这些手抄与朗读时最容易混的字符。
     *
     * <p>两处各存一份是没法避免的（没有共享构件），所以 {@code QrPayloadTest}
     * 把这个字母表与长度钉死在测试里：服务端换成 32 位字母表时，客户端必须同时改。
     */
    public static final String CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ";

    /** 绑定码长度，同样与服务端 {@code QrBindCodeService.CODE_LENGTH} 对齐。 */
    public static final int BIND_CODE_LENGTH = 12;

    public enum Kind {
        /** 受护人登录后出示的绑定码；监护人扫到就建立监护关系。 */
        BIND,
        /** 受护人未登录时出示的登录票据；监护人扫到后选择一位受护人授权登录。 */
        LOGIN
    }

    private final Kind kind;
    private final String value;

    private QrPayload(Kind kind, String value) {
        this.kind = kind;
        this.value = value;
    }

    @NonNull
    public Kind kind() {
        return kind;
    }

    /** 绑定码的短码，或登录票据的 ticket（均已归一化）。 */
    @NonNull
    public String value() {
        return value;
    }

    /** 生成要画进二维码的文本。 */
    @NonNull
    public String encode() {
        return kind == Kind.BIND ? BIND_PREFIX + value : LOGIN_PREFIX + value;
    }

    @NonNull
    public static QrPayload bind(@NonNull String code) {
        return new QrPayload(Kind.BIND, normalizeCode(code));
    }

    @NonNull
    public static QrPayload login(@NonNull String ticket) {
        return new QrPayload(Kind.LOGIN, ticket.trim());
    }

    /**
     * 解析扫到或手输的文本。
     *
     * <p>裸码按<b>形状</b>判定类型：长度与字母表都对得上 12 位绑定码的算 {@code BIND}，
     * 其余算 {@code LOGIN}。这样手动输入绑定码能通，而监护人扫到别人的裸票据也仍是票据。
     * 反过来（把票据误判成绑定码）不可能发生：票据是 Base64url，
     * 含小写字母和 {@code -_}，不在绑定码字母表内。
     *
     * @return 解析不出内容时返回 {@code null}，由调用方提示「这不是有效的二维码」
     */
    @Nullable
    public static QrPayload parse(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        // scheme 大小写不敏感地比对（别的 App 转发过来时大小写可能被改），
        // 但取值从原文里切，票据是 Base64url、大小写有意义。
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith(BIND_PREFIX)) {
            String code = normalizeCode(text.substring(BIND_PREFIX.length()));
            return code.isEmpty() ? null : new QrPayload(Kind.BIND, code);
        }
        if (lower.startsWith(LOGIN_PREFIX)) {
            String ticket = text.substring(LOGIN_PREFIX.length()).trim();
            return ticket.isEmpty() ? null : new QrPayload(Kind.LOGIN, ticket);
        }
        // 别的 sullivan:// 路径（未来可能加的）认不出来就说不认识，
        // 不要退化成裸码去猜 —— 猜错会让监护人把一段垃圾当绑定码发出去。
        if (lower.startsWith(SCHEME)) {
            return null;
        }
        String code = normalizeCode(text);
        if (code.length() == BIND_CODE_LENGTH) {
            return new QrPayload(Kind.BIND, code);
        }
        return new QrPayload(Kind.LOGIN, text);
    }

    /** 是否长得像一张合法的绑定码（供「手动输入」框做即时校验，提前给出提示）。 */
    public static boolean looksLikeBindCode(@Nullable String raw) {
        QrPayload parsed = parse(raw);
        return parsed != null && parsed.kind() == Kind.BIND;
    }

    /**
     * 把绑定码归一化成「只含字母表内大写字符」的形式。
     *
     * <p>手输时会被自动修正的有：小写字母、空格（分组抄写）、以及
     * {@code 0→O…} 这一类形近误认。这里只做大小写与空白，
     * 形近字符的纠正交给「字母表里本来就没有 0/O/1/I」这件事去兜住。
     */
    @NonNull
    private static String normalizeCode(@NonNull String raw) {
        String upper = raw.trim().toUpperCase(java.util.Locale.ROOT);
        StringBuilder builder = new StringBuilder(upper.length());
        for (int i = 0; i < upper.length(); i++) {
            char character = upper.charAt(i);
            if (CODE_ALPHABET.indexOf(character) >= 0) {
                builder.append(character);
            }
        }
        return builder.toString();
    }
}
