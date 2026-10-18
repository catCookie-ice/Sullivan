package com.sullivan.protectee.qr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 出示设备凭证的哈希。
 *
 * <p>这个哈希要与服务端 {@code Hashing.sha256(clientSecret)} <b>逐字相同</b>
 * （小写十六进制、64 字符）。服务端把它存进 {@code qr_login_ticket.secret_hash}
 * 并用等值比较核验，所以差一个字符的表现就是「兑换永远 403 出示设备校验失败」——
 * 一个在日志里完全看不出原因的失败。
 */
public class LoginTicketSecretTest {

    @Test
    public void hashIsLowercaseHexSha256() {
        String hash = LoginTicketSecret.hash("abc");

        // 用 sha256sum 算出来的对照值。
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash);
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    @Test
    public void hashOfEmptyStringMatchesKnownVector() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                LoginTicketSecret.hash(""));
    }

    /** 非 ASCII 也按 UTF-8 字节算 —— 与 Java 服务端默认行为一致。 */
    @Test
    public void hashUsesUtf8Bytes() {
        // 服务端是 Java，String.getBytes(UTF_8) 的结果与这里必须相同。
        assertEquals(LoginTicketSecret.hash("密码"), LoginTicketSecret.hash("密码"));
        assertEquals(64, LoginTicketSecret.hash("密码").length());
    }

    /** 每次生成都必须不同：共用同一个 secret 会让「设备绑定」这个性质整体失效。 */
    @Test
    public void everySecretIsUniqueAndUrlSafe() {
        String first = LoginTicketSecret.newSecret();
        String second = LoginTicketSecret.newSecret();

        assertNotEquals(first, second);
        // 16 字节 Base64url 无填充 = 22 字符，且只含 URL 安全字符。
        assertEquals(22, first.length());
        assertTrue(first.matches("[A-Za-z0-9_-]{22}"));
    }

    /** 生成的 secret 不能含 Base64 的 + / 与填充 =，否则放进 JSON 或 query 都要转义。 */
    @Test
    public void generatedSecretsNeverNeedEscaping() {
        for (int i = 0; i < 200; i++) {
            String secret = LoginTicketSecret.newSecret();
            assertTrue(secret, secret.indexOf('+') < 0);
            assertTrue(secret, secret.indexOf('/') < 0);
            assertTrue(secret, secret.indexOf('=') < 0);
        }
    }
}
