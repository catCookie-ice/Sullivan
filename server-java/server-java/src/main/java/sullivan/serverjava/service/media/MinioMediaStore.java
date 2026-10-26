package sullivan.serverjava.service.media;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * MinIO 实现。部署环境用（{@code app.media.store=minio}），对应 `05` §6 的约定。
 *
 * <p><b>桶在启动时确保存在</b>，而不是第一次写入时再建：写入路径上多一次
 * 网络往返，且那次失败会表现为「留言发不出去」，排查时很难想到是桶没建。
 *
 * <p><b>不生成预签名 URL。</b>预签名 URL 一旦发出去就无法撤回，而语音留言的
 * 授权判断（「你是不是投递双方」）必须在每次取回时重新做。所以取回走
 * {@link #get(String)} + 服务端流式转发，客户端拿到的是经过鉴权的内容，
 * 不是一个自带授权的地址。
 */
@Component
@ConditionalOnProperty(name = "app.media.store", havingValue = "minio")
public class MinioMediaStore implements MediaStore {
    private static final Logger log = LoggerFactory.getLogger(MinioMediaStore.class);

    private final MinioClient client;
    private final String bucket;

    public MinioMediaStore(
            @org.springframework.beans.factory.annotation.Value("${app.media.minio.endpoint:}") String endpoint,
            @org.springframework.beans.factory.annotation.Value("${app.media.minio.access-key:}") String accessKey,
            @org.springframework.beans.factory.annotation.Value("${app.media.minio.secret-key:}") String secretKey,
            @org.springframework.beans.factory.annotation.Value("${app.media.minio.bucket:sullivan-media}") String bucket) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException(
                    "app.media.store=minio 时必须配置 app.media.minio.endpoint（如 http://127.0.0.1:9000）");
        }
        this.bucket = bucket;
        this.client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        ensureBucket();
        log.info("媒体存储：MinIO {} / bucket={}", endpoint, bucket);
    }

    private void ensureBucket() {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("已创建 MinIO 桶 {}", bucket);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("MinIO 桶不可用: " + bucket, exception);
        }
    }

    @Override
    public void put(String objectKey, InputStream stream, long size, String contentType) {
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    // size 已知就传确切长度；partSize 设为 -1 表示「不分片，按 size 一次传」，
                    // 语音留言最多几十秒（几百 KB），分片没有收益。
                    .stream(stream, size, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception exception) {
            throw new IllegalStateException("写入 MinIO 对象失败: " + objectKey, exception);
        }
    }

    @Override
    public InputStream get(String objectKey) {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (ErrorResponseException exception) {
            String code = exception.errorResponse() == null ? "" : exception.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code)) {
                throw new MediaNotFoundException("媒体对象不存在: " + objectKey);
            }
            throw new IllegalStateException("读取 MinIO 对象失败: " + objectKey, exception);
        } catch (Exception exception) {
            throw new IllegalStateException("读取 MinIO 对象失败: " + objectKey, exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception exception) {
            log.warn("删除 MinIO 对象失败 {}: {}", objectKey, exception.getMessage());
        }
    }
}
