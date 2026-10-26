package sullivan.serverjava.service.media;

import com.qcloud.cos.COS;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.region.Region;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * 腾讯云 COS 实现（{@code app.media.store=cos}）。
 *
 * <p><b>为什么国内部署选它而不是自建 MinIO</b>：语音留言存的是**用户真实的声音**，
 * 丢了找不回来。自建 MinIO 要自己保证磁盘冗余、备份与可用性，而对象存储把这些
 * 变成配置项。代价是数据出网要过公网，因此桶必须是私有的，且只经服务端读写。
 *
 * <p><b>仍然不发预签名 URL。</b>和 MinIO 实现一样，取回走 {@link #get(String)} +
 * 服务端流式转发：授权判断（「你是不是投递双方」）必须在**每次**取回时重做。
 * 预签名 URL 一旦发出去，在有效期内就撤不回，解绑后「立刻取不到」这条保证会退化成
 * 「最多等 URL 过期」。这条约束由 `05` §6 与 `22` 之外的会话设计共同确定。
 *
 * <p><b>桶必须私有。</b>对象键里不含账号 ID，但键一旦泄露（日志、报错、截图）
 * 就等同于文件泄露 —— 前提正是「桶不公开」。若桶设成公有读，这里的所有鉴权都白做。
 */
@Component
@ConditionalOnProperty(name = "app.media.store", havingValue = "cos")
public class CosMediaStore implements MediaStore {
    private static final Logger log = LoggerFactory.getLogger(CosMediaStore.class);

    private final COS cos;
    private final String bucket;
    /** 对象键前缀，用于把不同环境（如测试、生产）的数据隔在同一个桶里。 */
    private final String prefix;

    @Autowired
    public CosMediaStore(
            @Value("${app.media.cos.secret-id:}") String secretId,
            @Value("${app.media.cos.secret-key:}") String secretKey,
            @Value("${app.media.cos.region:}") String region,
            @Value("${app.media.cos.bucket:}") String bucket,
            @Value("${app.media.cos.prefix:}") String prefix) {
        this(buildClient(secretId, secretKey, region), bucket, prefix);
        log.info("媒体存储：腾讯云 COS region={} bucket={} prefix={}", region, bucket,
                this.prefix.isEmpty() ? "(无)" : this.prefix);
    }

    /** 包内可见，供单元测试注入假的 {@link COS}。 */
    CosMediaStore(COS cos, String bucket, String prefix) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("app.media.store=cos 时必须配置 app.media.cos.bucket");
        }
        // COS 的桶名必须带 APPID 后缀（形如 sullivan-1250000000）。
        // 只写「sullivan」时 SDK 报的是「桶不存在」，与真正的原因（名字少了一半）
        // 看起来毫无关系，很容易往权限方向排查。所以在启动时就拦住。
        int dash = bucket.lastIndexOf('-');
        boolean hasAppId = dash > 0 && dash < bucket.length() - 1
                && bucket.substring(dash + 1).chars().allMatch(Character::isDigit);
        if (!hasAppId) {
            throw new IllegalStateException(
                    "app.media.cos.bucket 必须带 APPID 后缀，形如 sullivan-1250000000；当前值：" + bucket);
        }
        this.cos = cos;
        this.bucket = bucket;
        this.prefix = normalizePrefix(prefix);
    }

    private static COS buildClient(String secretId, String secretKey, String region) {
        if (secretId == null || secretId.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException(
                    "app.media.store=cos 时必须配置 app.media.cos.secret-id 与 app.media.cos.secret-key");
        }
        if (region == null || region.isBlank()) {
            throw new IllegalStateException(
                    "app.media.store=cos 时必须配置 app.media.cos.region（如 ap-guangzhou）");
        }
        ClientConfig config = new ClientConfig(new Region(region));
        // 强制 HTTPS：语音内容是个人信息，不该以明文经过中间网络。
        // SDK 默认也会走 https，这里显式写出来是为了不让它被环境变量意外改掉。
        config.setHttpProtocol(HttpProtocol.https);
        return new COSClient(new BasicCOSCredentials(secretId, secretKey), config);
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String trimmed = prefix.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** 业务层给的键不带前缀；前缀只在存储层拼接，业务代码不需要知道它的存在。 */
    private String fullKey(String objectKey) {
        return prefix.isEmpty() ? objectKey : prefix + "/" + objectKey;
    }

    @Override
    public void put(String objectKey, InputStream stream, long size, String contentType) {
        String key = fullKey(objectKey);
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(size);
            metadata.setContentType(contentType);
            cos.putObject(bucket, key, stream, metadata);
        } catch (CosClientException exception) {
            // 不把 secretKey/签名信息带进异常消息：它们可能出现在日志与报错页里。
            throw new IllegalStateException("写入 COS 对象失败: " + key, exception);
        }
    }

    @Override
    public InputStream get(String objectKey) {
        String key = fullKey(objectKey);
        try {
            COSObject object = cos.getObject(bucket, key);
            return object.getObjectContent();
        } catch (CosServiceException exception) {
            // 404 / NoSuchKey 是「对象不存在」，业务层要据此返回 404 而不是 500。
            // 其余服务端错误一律按 500 处理，不要伪装成「没找到」。
            if (exception.getStatusCode() == 404 || "NoSuchKey".equals(exception.getErrorCode())) {
                throw new MediaNotFoundException("媒体对象不存在: " + key);
            }
            throw new IllegalStateException("读取 COS 对象失败: " + key, exception);
        } catch (CosClientException exception) {
            throw new IllegalStateException("读取 COS 对象失败: " + key, exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        String key = fullKey(objectKey);
        try {
            cos.deleteObject(bucket, key);
        } catch (CosClientException exception) {
            // 删除失败只记日志：调用方已经做完了它的语义（把自己这一侧标记为已删除），
            // 因为一次存储抖动就让「删除留言」整体失败，对用户是更糟的体验。
            log.warn("删除 COS 对象失败 {}: {}", key, exception.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        if (cos instanceof COSClient client) {
            client.shutdown();
        }
    }
}
