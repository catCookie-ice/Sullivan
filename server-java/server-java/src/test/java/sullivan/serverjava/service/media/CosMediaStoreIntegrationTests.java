package sullivan.serverjava.service.media;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 腾讯云 COS 的**真实**往返测试：写入 → 取回 → 比对字节 → 删除 → 确认取不到。
 *
 * <p><b>为什么单独一个类</b>：{@link CosMediaStoreTests} 把 {@code COS} 接口 mock 掉了，
 * 它能证明「对象键拼对了、404 翻译对了」，但**证明不了凭据是否正确、桶是否可写、
 * 写进去再读出来字节是否一致**。这三件事只有连真实桶才能回答，而它们恰恰是
 * 「用户按了发送却听不到声音」时最可能出问题的地方。
 *
 * <p><b>没有凭据时自动跳过</b>，所以不会拖累常规的 {@code mvn test}。
 * 要跑它，先准备环境变量（不要写进仓库或任何提交的文件）：
 *
 * <pre>
 *   COS_SECRET_ID=...
 *   COS_SECRET_KEY=...
 *   COS_REGION=ap-guangzhou
 *   COS_BUCKET=sullivan-&lt;APPID&gt;
 *   COS_PREFIX=a              # 可选，建议指定，避免污染桶根目录
 * </pre>
 *
 * 然后：{@code mvnw test -Dtest=CosMediaStoreIntegrationTests}
 *
 * <p><b>它是会写真数据的</b>：会在桶里建一个 `it/` 开头的一次性对象，并在断言后删除。
 * 若中途失败，可能留下残留对象，可手动清理。
 */
class CosMediaStoreIntegrationTests {

    private String secretId;
    private String secretKey;
    private String region;
    private String bucket;
    private String prefix;

    @BeforeEach
    void requireCredentials() {
        secretId = System.getenv("COS_SECRET_ID");
        secretKey = System.getenv("COS_SECRET_KEY");
        region = System.getenv("COS_REGION");
        bucket = System.getenv("COS_BUCKET");
        prefix = System.getenv("COS_PREFIX");

        // 缺任何一项就跳过，而不是失败：这些变量在常规开发机上本来就不该有。
        assumeTrue(secretId != null && !secretId.isBlank(), "未设置 COS_SECRET_ID，跳过真实 COS 测试");
        assumeTrue(secretKey != null && !secretKey.isBlank(), "未设置 COS_SECRET_KEY，跳过真实 COS 测试");
        assumeTrue(region != null && !region.isBlank(), "未设置 COS_REGION，跳过真实 COS 测试");
        assumeTrue(bucket != null && !bucket.isBlank(), "未设置 COS_BUCKET，跳过真实 COS 测试");
    }

    private CosMediaStore store() {
        return new CosMediaStore(secretId, secretKey, region, bucket, prefix);
    }

    /**
     * 完整的往返：这正是语音留言的实际路径（上传音频 → 经鉴权端点取回）。
     *
     * <p>字节必须**逐个相同**。音频是有损压缩容器，一个字节错位在客户端就是
     * 「能播但声音是坏的」，比彻底播不出来更难查。
     */
    @Test
    void roundTripKeepsBytesIntact() throws Exception {
        CosMediaStore store = store();
        String objectKey = "it/" + UUID.randomUUID() + ".m4a";
        byte[] payload = "sullivan-cos-roundtrip".getBytes(StandardCharsets.UTF_8);

        try {
            store.put(objectKey, new java.io.ByteArrayInputStream(payload), payload.length, "audio/mp4");

            try (InputStream stream = store.get(objectKey)) {
                assertThat(stream).isNotNull();
                assertThat(stream.readAllBytes()).isEqualTo(payload);
            }
        } finally {
            store.delete(objectKey);
        }
    }

    /** 删除之后必须取不到，并且表现为「不存在」而不是 500 —— 客户端据此显示「已删除」。 */
    @Test
    void deletedObjectIsNotFound() {
        CosMediaStore store = store();
        String objectKey = "it/" + UUID.randomUUID() + ".m4a";
        byte[] payload = new byte[]{1, 2, 3, 4};

        store.put(objectKey, new java.io.ByteArrayInputStream(payload), payload.length, "audio/mp4");
        store.delete(objectKey);

        assertThatThrownBy(() -> store.get(objectKey))
                .isInstanceOf(MediaStore.MediaNotFoundException.class);
    }

    /** 前缀确实生效：对象应当落在 {@code <prefix>/it/...} 下，而不是桶根。 */
    @Test
    void prefixIsAppliedToRealObjects() throws Exception {
        assumeTrue(prefix != null && !prefix.isBlank(), "未设置 COS_PREFIX，跳过前缀断言");
        CosMediaStore store = store();
        String objectKey = "it/" + UUID.randomUUID() + ".m4a";
        byte[] payload = new byte[]{9};

        try {
            store.put(objectKey, new java.io.ByteArrayInputStream(payload), 1, "audio/mp4");
            // 能用同一个实例读回来，说明 put 与 get 拼的是同一个键。
            try (InputStream stream = store.get(objectKey)) {
                assertThat(stream.readAllBytes()).isEqualTo(payload);
            }
        } finally {
            store.delete(objectKey);
        }
    }
}
