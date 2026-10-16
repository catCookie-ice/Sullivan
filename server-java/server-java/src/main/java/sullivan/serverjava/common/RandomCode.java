package sullivan.serverjava.common;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 服务端签发的随机码工具：二维码绑定码与登录票据共用的唯一随机来源。
 *
 * <p>两种形态对应两类使用场景：</p>
 * <ul>
 *   <li>{@link #dictatable(int)} —— 需要人念出来/手输的短码（绑定码的兜底路径）。
 *       字母表去掉了 {@code 0 O 1 I L U}，避免低视力用户与监护人在朗读、默写时混淆。
 *       30 字符约 4.9 bit/字符，12 位约 59 bit。绑定码 TTL 只有两分钟且每位受护人
 *       同时只有一张有效码，在线穷举不可行。</li>
 *   <li>{@link #opaque(int)} —— 只进二维码、不需要人读的票据，用 Base64-url 取满熵，
 *       16 字节即 128 bit。</li>
 * </ul>
 */
public final class RandomCode {
    /** 32 位随机源；排除易混字符后的字母表。 */
    private static final char[] DICTATABLE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    private RandomCode() {
    }

    /** 生成可由人朗读/手输的不透明短码（字母表见类注释）。 */
    public static String dictatable(int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("length must be positive");
        }
        StringBuilder result = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            result.append(DICTATABLE_ALPHABET[RANDOM.nextInt(DICTATABLE_ALPHABET.length)]);
        }
        return result.toString();
    }

    /** 生成 Base64-url（无填充）不透明串，{@code byteLength} 字节随机源。 */
    public static String opaque(int byteLength) {
        if (byteLength <= 0) {
            throw new IllegalArgumentException("byteLength must be positive");
        }
        byte[] random = new byte[byteLength];
        RANDOM.nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }
}
