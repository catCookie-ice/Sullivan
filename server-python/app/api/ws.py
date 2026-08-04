"""
WebSocket 帧传输主通道 (docs/final/13 §3.4)。

协议：
- 建立连接时 query param 携带 token（JWT 校验）
- 每帧头部携带 session_id 与递增 sample_seq
- 服务端丢弃 sample_seq < 已处理序号的乱序帧
- 消息类型: FRAME / RESULT / CONTROL / ERROR
"""
from fastapi import APIRouter, WebSocket, WebSocketDisconnect

router = APIRouter()


@router.websocket("/ws/navigation/frame")
async def frame_stream(websocket: WebSocket):
    """导航帧流 WebSocket 入口"""
    await websocket.accept()
    try:
        while True:
            data = await websocket.receive_bytes()
            # TODO(M3): JWT 校验 → 帧解析 → 推理 → 空间分析 → 引导 → 返回结果
            await websocket.send_json({"status": "received", "seq": 0})
    except WebSocketDisconnect:
        pass
