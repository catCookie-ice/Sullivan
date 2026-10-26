package com.sullivan.protectee.service.voice;

import android.media.MediaRecorder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;

/**
 * 语音留言的录音器（MediaRecorder 的薄封装）。
 *
 * <p><b>为什么用 MediaRecorder 而不是 AudioRecord</b>：留言是「说完就发」的一段音频，
 * 不需要实时处理波形。MediaRecorder 直接把编码好的 m4a 写进文件，
 * 省掉一整套 PCM → 编码器的代码；AudioRecord 拿到的裸 PCM 还得自己编码，
 * 而这意味着再引一个编码库。
 *
 * <p><b>输出格式与 {@code VoiceMessageService.ALLOWED_CONTENT_TYPES} 对齐</b>：
 * MPEG_4 容器 + AAC 音频 = {@code audio/mp4}，这是服务端白名单里的一项，
 * 也是所有 Android 版本都能录制与播放的组合。
 */
public final class VoiceRecorder {

    /** 服务端接受的容器类型。改这里必须同步服务端白名单。 */
    public static final String CONTENT_TYPE = "audio/mp4";

    /** 服务端 {@code app.voice-message.max-duration-ms} 的默认值。 */
    public static final int MAX_DURATION_MS = 60_000;

    @Nullable
    private MediaRecorder recorder;
    @Nullable
    private File outputFile;
    private long startedAt;

    /**
     * 开始录音。
     *
     * @param directory 录音文件的落盘目录（本应用缓存目录）
     * @return 失败原因；{@code null} 表示成功
     */
    @Nullable
    public String start(@NonNull File directory) {
        stop();
        if (!directory.exists() && !directory.mkdirs()) {
            return "无法创建录音目录";
        }
        File file = new File(directory, "voice-" + System.currentTimeMillis() + ".m4a");
        MediaRecorder created = new MediaRecorder();
        try {
            created.setAudioSource(MediaRecorder.AudioSource.MIC);
            created.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            // 16 kHz 单声道 32 kbps：语音够清楚，一分钟约 240 KB，
            // 离服务端 1 MB 的上限还有足够余量。
            created.setAudioSamplingRate(16_000);
            created.setAudioChannels(1);
            created.setAudioEncodingBitRate(32_000);
            created.setOutputFile(file.getAbsolutePath());
            created.prepare();
            created.start();
        } catch (IOException | RuntimeException error) {
            created.release();
            // 文件已经建出来了但没录进去，删掉，别留一堆空壳。
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return "录音启动失败：" + error.getMessage();
        }
        recorder = created;
        outputFile = file;
        startedAt = System.currentTimeMillis();
        return null;
    }

    /**
     * 停止录音。
     *
     * @return 录好的文件；从没开始录或录音失败过则返回 {@code null}
     */
    @Nullable
    public File stop() {
        MediaRecorder current = recorder;
        recorder = null;
        File file = outputFile;
        outputFile = null;
        if (current == null) {
            return null;
        }
        try {
            current.stop();
        } catch (RuntimeException error) {
            // 一次都没采到数据时 stop() 会抛（比如刚 start 就 stop）。
            // 这种录音没有内容，删掉文件当作没录过。
            current.release();
            if (file != null) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
            return null;
        }
        current.release();
        return file;
    }

    /** 已经录了多久（毫秒）。未在录音时返回 0。 */
    public long elapsedMs() {
        return recorder == null ? 0 : System.currentTimeMillis() - startedAt;
    }

    public boolean isRecording() {
        return recorder != null;
    }

    /** Activity 被销毁时务必调用：不释放会让麦克风一直被占着，下次录音直接失败。 */
    public void release() {
        MediaRecorder current = recorder;
        recorder = null;
        outputFile = null;
        if (current != null) {
            try {
                current.stop();
            } catch (RuntimeException ignored) {
                // 已经停了。
            }
            current.release();
        }
    }
}
