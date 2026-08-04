"""Pydantic 领域模型"""
from pydantic import BaseModel, Field
from typing import Optional


class FrameRequest(BaseModel):
    """客户端上传的一帧数据"""
    session_id: str
    sample_seq: int = Field(gt=0)
    image_bytes: bytes = Field(max_length=512 * 1024)


class FrameResult(BaseModel):
    """服务端返回的分析结果"""
    session_id: str
    sample_seq: int
    risk_level: str  # SAFE / CAUTION / DANGER / UNKNOWN
    guide_text: str
    obstacles: list[dict] = []
    inference_ms: int
    processed_at: str


class NavigationGuide(BaseModel):
    """引导文本 (FR-08)"""
    risk_level: str
    guide_text: str
    priority: int  # 0=SAFE ... 3=UNKNOWN
    is_repeat: bool = False
