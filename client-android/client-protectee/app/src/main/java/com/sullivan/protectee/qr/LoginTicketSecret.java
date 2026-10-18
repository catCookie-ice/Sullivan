package com.sullivan.protectee.qr;

import androidx.annotation.NonNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 登录票据的「出示设备凭证」。
 *
 * <p>这是整套扫码登录里最容易做错的一环，说明白它防的是什么：
 *
 * <p>出示设备建票时生成一个随机 {@code clientSecret} 留在**本机**，
 * 只把 {@code sha256(clientSecret)} 发给服务端。二维码里只有 ticket，没有 secret。
 * 监护人确认之后，出示设备把 secret 连同服务端给的 exchangeCode 一起提交，
 * 三个条件全对才换得到令牌。于是：
 * <ul>
 *   <li>看见二维码（或拍到屏幕）的人<b>换不出令牌</b> —— 他没有 secret；</li>
 *   <li>服务端数据库被读走也解不出 secret（存的是哈希）；</li>
 *   <li>换令牌的请求即使被同网段截获，secret 也在这次之后就作废了
 *       （票据一次性核销）。</li>
 * </ul>
 *
 * <p><b>它挡不住什么也要写清楚</b>：明文 HTTP 下，同网段的嗅探者可以
 * 直接从建票请求里读到 {@code secretHash}、从兑换请求里读到 secret 本身，
 * 于是上面三条同时失效。设备绑定在网络攻击者面前是无效的 ——
 * 而网络攻击者恰恰是扫码登录最现实的威胁模型。这条已登记为已知风险
 * （见 {@code docs/final/17-开发日记-2026-10-16.md}），上 TLS 才能解。
 */
public final class LoginTicketSecret {

    /**
     * 16 字节 = 128 位。与登录票据本身同强度：
     * 两者都是「猜不中就行」的凭证，没必要一个 128 位、另一个 8 位。
     */
    private static final int SECRET_BYTES = 16;

    private static final SecureRandom RANDOM = new SecureRandom();

    private LoginTicketSecret() {
    }

    /** 生成一个新的出示设备凭证（Base64url、无填充、22 字符）。 */
    @NonNull
    public static String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * {@code sha256} 的十六进制小写形式（64 字符）。
     *
     * <p>服务端用 {@code @Pattern(regexp = "[0-9a-f]{64}")} 校验这个字段，
     * 大小写与位数都不能变；服务端存的是同一算法同一编码的哈希。
     */
    @NonNull
    public static String hash(@NonNull String clientSecret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(clientSecret.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hashed.length * 2);
            for (byte value : hashed) {
                builder.append(Character.forDigit((value >> 4) & 0xF, 16));
                builder.append(Character.forDigit(value & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException error) {
            // 任何 Android 平台都必须有 SHA-256；走到这里说明运行环境已经不正常了。
            throw new IllegalStateException("当前平台不支持 SHA-256", error);
        }
    }
}
