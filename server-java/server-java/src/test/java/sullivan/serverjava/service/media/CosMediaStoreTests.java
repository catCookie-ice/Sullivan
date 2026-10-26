package sullivan.serverjava.service.media;

import com.qcloud.cos.COS;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.ObjectMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CosMediaStore} 的单元测试。
 *
 * <p>用 Mockito 顶替 {@link COS} 接口，因此**不需要真实凭据、也不联网**。
 * 真正「能不能把对象存进腾讯云」只能在拿到桶的凭据后实测，
 * 这里锁住的是那些**配错也不报错**的地方：对象键前缀是否一致、
 * 404 是否被翻译成「不存在」而不是 500、桶名缺 APPID 是否在启动期就被拦住。
 */
class CosMediaStoreTests {
    private static final String BUCKET = "sullivan-1250000000";
    private static final String OBJECT_KEY = "voice/2026/10/26/abc.m4a";

    private COS cos;

    @BeforeEach
    void setUp() {
        cos = mock(COS.class);
    }

    // ------------------------------------------------------------------
    // 配置校验：这些错「看起来像权限问题」，必须在启动期就拦住
    // ------------------------------------------------------------------

    /**
     * 桶名缺 APPID 后缀时直接拒绝启动。
     *
     * <p>只写 {@code sullivan} 时 SDK 报的是「桶不存在」，
     * 与真正的原因（名字少了一半）看起来毫无关系，很容易往权限方向排查。
     */
    @Test
    void bucketWithoutAppIdRejected() {
        assertThatThrownBy(() -> new CosMediaStore(cos, "sullivan", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APPID");
    }

    @Test
    void blankBucketRejected() {
        assertThatThrownBy(() -> new CosMediaStore(cos, "  ", ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingSecretRejectedAtStartup() {
        assertThatThrownBy(() -> new CosMediaStore("", "", "ap-guangzhou", BUCKET, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("secret-id");
    }

    @Test
    void missingRegionRejectedAtStartup() {
        assertThatThrownBy(() -> new CosMediaStore("id", "key", "  ", BUCKET, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("region");
    }

    // ------------------------------------------------------------------
    // 对象键：前缀只在存储层拼，业务层无感
    // ------------------------------------------------------------------

    /** 写入时把前缀拼上，并带上内容类型与长度。 */
    @Test
    void putAppliesPrefixAndMetadata() {
        CosMediaStore store = new CosMediaStore(cos, BUCKET, "sullivan-test");
        byte[] payload = "audio".getBytes(StandardCharsets.UTF_8);

        store.put(OBJECT_KEY, new ByteArrayInputStream(payload), payload.length, "audio/mp4");

        ArgumentCaptor<ObjectMetadata> metadata = ArgumentCaptor.forClass(ObjectMetadata.class);
        verify(cos).putObject(eq(BUCKET), eq("sullivan-test/" + OBJECT_KEY),
                any(InputStream.class), metadata.capture());
        assertThat(metadata.getValue().getContentType()).isEqualTo("audio/mp4");
        assertThat(metadata.getValue().getContentLength()).isEqualTo(payload.length);
    }

    /** 无前缀时键就是业务层给的原值，不能被拼出多余的斜杠。 */
    @Test
    void putWithoutPrefixKeepsKeyUnchanged() {
        CosMediaStore store = new CosMediaStore(cos, BUCKET, "");
        byte[] payload = new byte[]{1, 2, 3};

        store.put(OBJECT_KEY, new ByteArrayInputStream(payload), payload.length, "audio/mp4");

        verify(cos).putObject(eq(BUCKET), eq(OBJECT_KEY), any(InputStream.class), any(ObjectMetadata.class));
    }

    /** 前缀两端的斜杠要归一，否则会生成 {@code /sullivan-test//voice/...} 这种键。 */
    @Test
    void prefixIsNormalized() {
        CosMediaStore store = new CosMediaStore(cos, BUCKET, "/sullivan-test//");
        byte[] payload = new byte[]{1};

        store.put(OBJECT_KEY, new ByteArrayInputStream(payload), 1, "audio/mp4");

        verify(cos).putObject(eq(BUCKET), eq("sullivan-test/" + OBJECT_KEY),
                any(InputStream.class), any(ObjectMetadata.class));
    }

    // ------------------------------------------------------------------
    // 读取与删除
    // ------------------------------------------------------------------

    @Test
    void getAppliesPrefixAndReturnsContent() {
        CosMediaStore store = new CosMediaStore(cos, BUCKET, "pfx");
        COSObject object = new COSObject();
        object.setObjectContent(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
        when(cos.getObject(BUCKET, "pfx/" + OBJECT_KEY)).thenReturn(object);

        InputStream stream = store.get(OBJECT_KEY);

        assertThat(stream).isNotNull();
    }

    /**
     * 对象不存在必须翻译成 {@link MediaStore.MediaNotFoundException}。
     *
     * <p>否则业务层无法把它映射成 404，用户删掉的留言会表现为「服务器错误」。
     */
    @Test
    void getOnMissingObjectBecomesNotFound() {
        CosMediaStore store = new CosMediaStore(cos, BUCKET, "");
        CosServiceException notFound = new CosServiceException("not found");
        notFound.setStatusCode(404);
        when(cos.getObject(anyString(), anyString())).thenThrow(notFound);

        assertThatThrownBy(() -> store.get(OBJECT_KEY))
                .isInstanceOf(MediaStore.MediaNotFoundException.class);
    }

    /** NoSuchKey 的 errorCode 也算「不存在」，因为它不一定带 404 状态码。 */
    @Test
    void getOnNoSuchKeyErrorCodeBecomesNotFound() {
        CosMediaStore store = new CosMediaStore(cos, BUCKET, "");
        CosServiceException noSuchKey = new CosServiceException("no such key");
        noSuchKey.setErrorCode("NoSuchKey");
        when(cos.getObject(anyString(), anyString())).thenThrow(noSuchKey);

        assertThatThrownBy(() -> store.get(OBJECT_KEY))
                .isInstanceOf(MediaStore.MediaNotFoundException.class);
    }

    /** 其它服务端错误不能被伪装成「不存在」，否则会把故障藏起来。 */
    @Test
    void getOnServiceErrorStaysInternalError() {
        CosMediaStore store = new CosMediaStore(cos, BUCKET, "");
        CosServiceException serverError = new CosServiceException("boom");
        serverError.setStatusCode(503);
        when(cos.getObject(anyString(), anyString())).thenThrow(serverError);

        assertThatThrownBy(() -> store.get(OBJECT_KEY))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(MediaStore.MediaNotFoundException.class);
    }

    /** 删除失败不能把「删除留言」整个操作打回去。 */
    @Test
    void deleteSwallowsClientException() {
        CosMediaStore store = new CosMediaStore(cos, BUCKET, "pfx");
        doThrow(new CosClientException("network down")).when(cos).deleteObject(BUCKET, "pfx/" + OBJECT_KEY);

        store.delete(OBJECT_KEY);

        verify(cos).deleteObject(BUCKET, "pfx/" + OBJECT_KEY);
    }
}
