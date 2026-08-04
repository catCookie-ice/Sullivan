"""
导航 REST 接口：会话创建/停止、单帧降级通道。
帧流主通道走 WebSocket (ws.py)。
"""
from fastapi import APIRouter, Depends, HTTPException

router = APIRouter()


@router.post("/sessions")
async def create_session():
    """创建导航会话 (FR-07)"""
    raise HTTPException(status_code=501, detail="TODO(M3)")


@router.post("/sessions/{session_id}/stop")
async def stop_session(session_id: str):
    """停止导航会话"""
    raise HTTPException(status_code=501, detail="TODO(M3)")


@router.post("/sessions/{session_id}/analyze-frame")
async def analyze_frame(session_id: str):
    """
    单帧分析 REST 降级通道。
    主通道为 WebSocket，此接口用于弱网回退与接口测试。
    """
    raise HTTPException(status_code=501, detail="TODO(M3)")
