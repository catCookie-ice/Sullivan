package sullivan.serverjava.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import sullivan.serverjava.common.ApiException;
import sullivan.serverjava.web.RealtimeDtos.IceServerResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 给客户端下发 STUN/TURN 配置。
 *
 * <p><b>凭据必须是临时的。</b>TURN 的长期凭据一旦写进客户端，就等于把一个
 * 「谁都能用来中转流量」的账号发了出去 —— 反编译就能拿到，而且撤不回。
 * 所以这里用 coturn 的 REST 风格临时凭据：用户名是 {@code <过期时间戳>:<账号 ID>}，
 * 密码是 {@code base64(HMAC-SHA1(secret, 用户名))}。服务端与 coturn 共享同一个
 * {@code static-auth-secret}，客户端拿到的凭据只在 TTL 内有效。
 *
 * <p><b>没配 TURN 时不伪装成功。</b>{@code app.webrtc.turn-urls} 为空时只返回 STUN
 * （局域网直连够用）。此时若两端不在同一网段，媒体直连会失败 —— 客户端必须把这
 * 如实告诉用户，而不是显示「已接通」。这是 01 §4 的接口约束，不是可选项。
 */
@Service
public class IceServerService {
    private static final Logger log = LoggerFactory.getLogger(IceServerService.class);

    private final List<String> stunUrls;
    private final List<String> turnUrls;
    private final String turnSecret;
    private final int ttlSeconds;

    public IceServerService(
            @Value("${app.webrtc.stun-urls:stun:stun.l.google.com:19302}") String stunUrls,
            @Value("${app.webrtc.turn-urls:}") String turnUrls,
            @Value("${app.webrtc.turn-secret:}") String turnSecret,
            @Value("${app.webrtc.credential-ttl-seconds:3600}") int ttlSeconds) {
        this.stunUrls = splitUrls(stunUrls);
        this.turnUrls = splitUrls(turnUrls);
        this.turnSecret = turnSecret == null ? "" : turnSecret.trim();
        this.ttlSeconds = ttlSeconds <= 0 ? 3600 : ttlSeconds;
    }

    /**
     * 下发配置。返回的 {@code username}/{@code credential} 只在 {@code ttlSeconds} 内有效。
     *
     * @param uid 用来构造临时用户名；它同时让每台设备的凭据互不相同，
     *            便于在 coturn 日志里定位是谁在用
     */
    public IceServerResponse forUser(String uid) {
        List<String> urls = new ArrayList<>(stunUrls);
        urls.addAll(turnUrls);
        if (urls.isEmpty()) {
            // 连 STUN 都没有：客户端只能靠主机候选直连，成功率取决于是否同网段。
            // 仍然返回空列表而不是抛错 —— 「没有可用的 ICE 服务器」是一个合法的
            // 运行状态，让客户端自己决定怎么提示，比在这里 500 更好。
            log.warn("webrtc: 既未配置 STUN 也未配置 TURN，媒体只能靠主机候选直连");
            return new IceServerResponse(List.of(), null, null, 0);
        }
        if (turnUrls.isEmpty() || turnSecret.isEmpty()) {
            // 只有 STUN：不需要凭据。这是本地开发与同网段演示的常见形态。
            return new IceServerResponse(urls, null, null, 0);
        }
        long expiry = System.currentTimeMillis() / 1000L + ttlSeconds;
        String username = expiry + ":" + uid;
        try {
            return new IceServerResponse(urls, username, hmacBase64(turnSecret, username), ttlSeconds);
        } catch (GeneralSecurityException exception) {
            // 算不出凭据时**不能**退回「给个空密码」：那会让 TURN 鉴权必然失败，
            // 而客户端看到的却是「有 TURN 配置」，排查方向会被带偏。
            log.error("webrtc: TURN 临时凭据生成失败: {}", exception.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "无法生成通话中转凭据");
        }
    }

    private static String hmacBase64(String secret, String message) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return Base64.getEncoder().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<String> splitUrls(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
