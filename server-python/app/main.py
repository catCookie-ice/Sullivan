"""
    Sullivan Python 部分 — 全景分割推理 + 空间分析 + 引导文本

技术栈: FastAPI + ONNX Runtime CUDA EP + SQLAlchemy 2.0
文档: docs/final/13 §3-§4
"""

from fastapi import FastAPI
from app.api.rest import router as rest_router
from app.api.ws import router as ws_router
from app.core.config import settings

app = FastAPI(
    title="Sullivan Python Navigation",
    version="0.1.0",
    description="全景分割辅助导航 — 帧推理、空间分析、引导文本生成",
)

app.include_router(rest_router, prefix="/api/v1/navigation")
app.include_router(ws_router)


@app.get("/actuator/health")
async def health():
    return {"status": "UP", "application": "sullivan-python"}
