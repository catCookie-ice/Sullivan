package sullivan.serverjava.realtime;

import java.io.IOException;

/**
 * 实时通道抽象：与具体 WebSocket 库解耦，使连接池只依赖通道与信封协议。
 * 未来 C++ 网关可提供远程通道实现替换本接口。
 */
public interface RealtimeChannel {
    /** 通道唯一标识（同一用户多设备以 socketId 区分）。 */
    String id();

    /** 通道所属账号 ID。 */
    String principalId();

    /** 是否仍处于打开状态。 */
    boolean isOpen();

    /** 发送一段文本帧。 */
    void send(String text) throws IOException;
}
