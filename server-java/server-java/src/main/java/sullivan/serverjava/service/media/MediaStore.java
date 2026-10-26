package sullivan.serverjava.service.media;

import java.io.InputStream;

/**
 * 媒体对象的读写接口。语音留言的音频经它落盘，业务层不关心背后是 MinIO 还是本地目录。
 *
 * <p><b>为什么要抽这一层</b>：部署环境用 MinIO（`05` §6 的约定），但开发机往往跑不起
 * MinIO 容器；若业务代码直接依赖 MinIO SDK，开发环境就变成「必须先起一个 MinIO」。
 * 抽一层之后，两种实现共用同一套对象键，切换只改配置。
 *
 * <p><b>对象键由服务端生成，客户端永远拿不到</b>。取回一律经鉴权端点，
 * 这样「知道 ID 就能下载别人的录音」在结构上就不可能。
 */
public interface MediaStore {

    /**
     * 写入一个对象。
     *
     * @param objectKey 服务端生成的对象键
     * @param stream    内容流（调用方负责关闭）
     * @param size      字节数；用于容量上限校验与内容长度声明
     * @param contentType MIME 类型，例如 {@code audio/mp4}
     */
    void put(String objectKey, InputStream stream, long size, String contentType);

    /**
     * 读取一个对象。调用方负责关闭返回的流。
     *
     * @throws MediaNotFoundException 对象不存在时抛出
     */
    InputStream get(String objectKey);

    /** 删除一个对象；对象不存在时静默返回（删除幂等）。 */
    void delete(String objectKey);

    /** 对象不存在。业务层据此返回 404 而不是 500。 */
    class MediaNotFoundException extends RuntimeException {
        public MediaNotFoundException(String message) {
            super(message);
        }
    }
}
