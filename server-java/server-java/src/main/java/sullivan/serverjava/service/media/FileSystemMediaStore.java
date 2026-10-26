package sullivan.serverjava.service.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 本地文件系统实现。开发与单机演示用，配置 {@code app.media.store=filesystem} 时生效。
 *
 * <p><b>对象键里的路径分隔符要被压平。</b>{@code objectKey} 由服务端生成（形如
 * {@code voice/2026/10/26/XXXX.m4a}），但如果哪天生成规则变了、带上了
 * {@code ..}，直接拼到根目录下就会写到目录外面去。这里统一拒绝任何含
 * {@code ..}、绝对路径或反斜杠的键 —— 这是本地实现的**唯一**安全边界，
 * 不能靠「调用方不会传坏值」来兜底。
 */
@Component
@ConditionalOnProperty(name = "app.media.store", havingValue = "filesystem", matchIfMissing = true)
public class FileSystemMediaStore implements MediaStore {
    private static final Logger log = LoggerFactory.getLogger(FileSystemMediaStore.class);

    private final Path root;

    public FileSystemMediaStore(
            @org.springframework.beans.factory.annotation.Value("${app.media.root:./data/media}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException exception) {
            // 起不来比「运行时第一次写入才发现目录不可用」好：后者会表现为
            // 「语音留言发不出去」，而真正的原因在启动时就该暴露。
            throw new IllegalStateException("无法创建媒体目录: " + this.root, exception);
        }
        log.info("媒体存储：本地文件系统 {}", this.root);
    }

    @Override
    public void put(String objectKey, InputStream stream, long size, String contentType) {
        Path target = resolve(objectKey, true);
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // 先写临时文件再原子移动：中途失败不会留下一个「看起来存在、其实截断」的音频。
            Path temp = Files.createTempFile(parent, "upload-", ".part");
            try {
                try (OutputStream out = Files.newOutputStream(temp)) {
                    stream.transferTo(out);
                }
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("写入媒体对象失败: " + objectKey, exception);
        }
    }

    @Override
    public InputStream get(String objectKey) {
        Path target = resolve(objectKey, false);
        if (!Files.isRegularFile(target)) {
            throw new MediaNotFoundException("媒体对象不存在: " + objectKey);
        }
        try {
            return Files.newInputStream(target);
        } catch (IOException exception) {
            throw new IllegalStateException("读取媒体对象失败: " + objectKey, exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        Path target = resolve(objectKey, false);
        try {
            Files.deleteIfExists(target);
        } catch (IOException exception) {
            log.warn("删除媒体对象失败 {}: {}", objectKey, exception.getMessage());
        }
    }

    /**
     * 把对象键解析成根目录下的路径，并确认它没有跑出根目录。
     *
     * @param createDirectories 写入路径时允许中间目录缺失；读取时不允许
     */
    private Path resolve(String objectKey, boolean createDirectories) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("对象键不能为空");
        }
        String normalised = objectKey.replace('\\', '/');
        if (normalised.contains("..") || normalised.startsWith("/")) {
            throw new IllegalArgumentException("非法的对象键: " + objectKey);
        }
        Path target = root.resolve(normalised).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("对象键越出媒体目录: " + objectKey);
        }
        return target;
    }
}
