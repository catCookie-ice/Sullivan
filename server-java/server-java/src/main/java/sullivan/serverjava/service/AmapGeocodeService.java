package sullivan.serverjava.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 高德「Web 服务」逆地理编码：坐标 → 地名。
 *
 * <p><b>为什么在服务端做</b>：报警位置的地名要展示在监护人端的**三处**
 * （来电通知、报警弹窗、会话页位置条），在报警创建时解析一次、随位置快照
 * 持久化并下发，客户端只负责「有就显示」，解析失败也不影响报警。
 *
 * <p><b>硬时限，绝不让地名拖慢报警。</b>解析在独立线程里跑，报警创建线程
 * 最多等 {@code app.amap.geocode-deadline-ms}（默认 1 秒）：到点没结果就放弃等待，
 * 返回 {@link GeoResolution#PENDING}，报警照常落库转发，并带上 pending 标识 ——
 * 接收方（监护人端）见到标识会调用自己内置的解析兜底。
 * 「把求助送出去」永远优先于「把地名查出来」。
 *
 * <p><b>Key 的类型</b>：restapi.amap.com 要求「Web 服务」类型的 Key，
 * 与客户端在用的「Android 平台」Key 不是同一种，需要在高德控制台单独创建，
 * 通过环境变量 {@code AMAP_WEB_KEY} 注入（见 application.properties）。
 * **未配置时直接短路**，不发起请求也不占时限。
 *
 * <p><b>失败是常态而非异常</b>：所有失败（无网、超时、配额、服务区外）
 * 统一返回无地名结果，调用方落 NULL 到库，客户端回落到坐标或本端解析。
 */
@Service
public class AmapGeocodeService {

    private static final Logger log = LoggerFactory.getLogger(AmapGeocodeService.class);

    /** 单次外部调用的连接/读取超时（毫秒）。是后台线程的内层上限，通常等不到它——外层时限先到。 */
    private static final int HTTP_TIMEOUT_MS = 2000;
    /** 缓存条数上限。到顶就整体清一轮过期项；报警频度下远到不了这么多次。 */
    private static final int CACHE_MAX_ENTRIES = 256;
    /** 缓存有效期（毫秒）。地名变化以天计，10 分钟只为吸收「同一地点反复报警」。 */
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    /** 一次解析的结果：{@code address} 为 null 表示没拿到；{@code pending} 为 true 表示是**等超时**放弃的。 */
    public record GeoResolution(String address, boolean pending) {
        /** 没拿到地名，且不是超时（无 Key / 坐标缺失 / 解析确定失败）。 */
        public static final GeoResolution NONE = new GeoResolution(null, false);
        /** 等到了时限还没拿到——报警先走，接收方自行解析。 */
        public static final GeoResolution PENDING = new GeoResolution(null, true);
    }

    private final RestClient restClient;
    private final String webKey;
    private final long deadlineMs;
    /** 单线程守护池足够：解析本来就有时限与缓存，串行化反而天然限流。 */
    private final ExecutorService geocodeExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "amap-geocode");
                thread.setDaemon(true);
                return thread;
            });

    /** key = 坐标约化到 3 位小数（约 111 米格网）的 "lat,lon"。 */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(long expireAt, String address) {
    }

    public AmapGeocodeService(
            @Value("${app.amap.web-key:}") String webKey,
            @Value("${app.amap.geocode-deadline-ms:1000}") long deadlineMs) {
        this.webKey = webKey == null ? "" : webKey.trim();
        this.deadlineMs = Math.max(0L, deadlineMs);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(HTTP_TIMEOUT_MS);
        factory.setReadTimeout(HTTP_TIMEOUT_MS);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @PreDestroy
    void shutdown() {
        geocodeExecutor.shutdownNow();
    }

    /**
     * 解析坐标对应的地名（省市区 + 道路/地标，已去掉「中国」前缀）。
     *
     * <p>**最多阻塞 {@code deadlineMs} 毫秒**；到点未完成返回 {@link GeoResolution#PENDING}，
     * 后台线程会被中断（HTTP 请求随之作废），不占用下一次解析。
     *
     * @return 结果；未配置 Key 或坐标为 null 时返回 {@link GeoResolution#NONE}
     */
    public GeoResolution resolve(BigDecimal latitude, BigDecimal longitude) {
        if (latitude == null || longitude == null || webKey.isEmpty()) {
            return GeoResolution.NONE;
        }
        String cacheKey = latitude.setScale(3, RoundingMode.HALF_UP).toPlainString()
                + "," + longitude.setScale(3, RoundingMode.HALF_UP).toPlainString();
        CacheEntry cached = cache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expireAt() > now) {
            return new GeoResolution(cached.address(), false);
        }

        Future<String> future = geocodeExecutor.submit(() -> fetch(latitude, longitude));
        try {
            String address = deadlineMs == 0
                    ? future.get() : future.get(deadlineMs, TimeUnit.MILLISECONDS);
            if (address != null) {
                if (cache.size() >= CACHE_MAX_ENTRIES) {
                    cache.values().removeIf(entry -> entry.expireAt() <= now);
                }
                cache.put(cacheKey, new CacheEntry(now + CACHE_TTL_MS, address));
                return new GeoResolution(address, false);
            }
            // 解析在时限内完成但没拿到结果（高德报错/空地址）：确定失败，不是超时。
            return GeoResolution.NONE;
        } catch (TimeoutException timeout) {
            // 时限已到：报警不等了。取消后台任务并如实告知调用方「超时未解析」。
            future.cancel(true);
            log.warn("高德逆地理编码超过 {} ms 时限，报警先行转发，接收方将本端兜底解析", deadlineMs);
            return GeoResolution.PENDING;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return GeoResolution.NONE;
        } catch (java.util.concurrent.ExecutionException error) {
            future.cancel(true);
            log.warn("高德逆地理编码后台任务异常", error.getCause());
            return GeoResolution.NONE;
        }
    }

    /** 调用高德 regeo 接口；一切失败都折叠成 null，只留一条 warn 日志。 */
    private String fetch(BigDecimal latitude, BigDecimal longitude) {
        try {
            JsonNode root = restClient.get()
                    .uri("https://restapi.amap.com/v3/geocode/regeo?key={key}"
                            + "&location={lon},{lat}&extensions=base",
                            webKey, longitude.toPlainString(), latitude.toPlainString())
                    .retrieve()
                    .body(JsonNode.class);
            // 高德的「成功」是 status == "1"；失败时 info 里有人话原因。
            if (root == null || !"1".equals(root.path("status").asText())) {
                log.warn("高德逆地理编码失败：lat={}, lon={}, info={}",
                        latitude, longitude, root == null ? "空响应" : root.path("info").asText());
                return null;
            }
            String address = root.path("regeocode").path("formatted_address").asText(null);
            if (address == null || address.isBlank()) {
                return null;
            }
            // 返回值以「中国」开头：都在国内，这层是废话，砍掉再存。
            return address.startsWith("中国") ? address.substring("中国".length()) : address;
        } catch (RuntimeException error) {
            // 超时/无网/5xx 都走这里。报警不能被一次外部调用卡死。
            log.warn("高德逆地理编码调用异常：lat={}, lon={}", latitude, longitude, error);
            return null;
        }
    }
}
