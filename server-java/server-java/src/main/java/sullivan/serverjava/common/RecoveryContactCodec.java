package sullivan.serverjava.common;

import org.springframework.http.HttpStatus;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/**
 * 找回联系方式半加密编解码。
 *
 * <p>存储时分两段：明文 {@code prefix}（不加密段）+ 单向加密 {@code cipherHash}。
 * <ul>
 *   <li>手机号：不加密后四位；剩余前段加密。</li>
 *   <li>邮箱：不加密 {@code @} 前 local 段的前 1/4（最多 4 字符）；其余（剩余 local + @域名）加密。</li>
 * </ul>
 * 不加密段同时作为本次加密的密钥（HMAC-SHA256），因此验证时按同规则重算比对即可，密文不可逆。</p>
 */
public final class RecoveryContactCodec {
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    /** 邮箱不加密段长度上限 */
    private static final int EMAIL_PREFIX_MAX = 4;
    /** 邮箱不加密段占 local 段的比例 */
    private static final int EMAIL_PREFIX_DENOMINATOR = 4;

    private RecoveryContactCodec() {
    }

    public record Segments(String type, String prefix, String remainder) {
    }

    /**
     * 将完整联系方式切为两段，并识别类型（PHONE / EMAIL）。
     *
     * @throws ApiException 格式非法（既不是手机号也不是邮箱）时抛出 400
     */
    public static Segments split(String contact) {
        String value = contact == null ? "" : contact.trim();
        if (PHONE_PATTERN.matcher(value).matches()) {
            String prefix = value.substring(value.length() - 4);
            String remainder = value.substring(0, value.length() - 4);
            return new Segments("PHONE", prefix, remainder);
        }
        String lower = value.toLowerCase();
        if (EMAIL_PATTERN.matcher(lower).matches()) {
            int at = lower.indexOf('@');
            String local = lower.substring(0, at);
            String domain = lower.substring(at);
            int prefixLen = Math.min(EMAIL_PREFIX_MAX,
                    (int) Math.ceil(local.length() / (double) EMAIL_PREFIX_DENOMINATOR));
            String prefix = local.substring(0, prefixLen);
            String remainder = local.substring(prefixLen) + domain;
            return new Segments("EMAIL", prefix, remainder);
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "找回联系方式格式不正确，仅支持手机号或邮箱");
    }

    /**
     * 单向加密剩余段：HMAC-SHA256，key=不加密段，输出 hex。
     */
    public static String cipher(String prefix, String remainder) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(prefix.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(remainder.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException exception) {
            throw new IllegalStateException(HMAC_ALGORITHM + " is unavailable", exception);
        }
    }

    /**
     * 供找回流程展示的掩码形式：手机 {@code ****8000}；邮箱 {@code zh****}。
     */
    public static String masked(String type, String prefix) {
        return switch (type) {
            case "PHONE" -> "****" + prefix;
            case "EMAIL" -> prefix + "****";
            default -> prefix;
        };
    }

    /**
     * 校验补全的联系方式是否与存储的两段一致（邮箱大小写不敏感）。
     */
    public static boolean matches(String contact, String storedPrefix, String storedCipher) {
        Segments segments;
        try {
            segments = split(contact);
        } catch (ApiException exception) {
            return false;
        }
        if (!segments.prefix().equals(storedPrefix)) {
            return false;
        }
        return MessageDigest.isEqual(
                cipher(segments.prefix(), segments.remainder()).getBytes(StandardCharsets.UTF_8),
                storedCipher.getBytes(StandardCharsets.UTF_8));
    }
}
