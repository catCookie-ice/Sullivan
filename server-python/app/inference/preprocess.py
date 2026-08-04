"""图像预处理 — 解码、缩放、归一化 (FR-07)"""
import cv2
import numpy as np


def decode_frame(frame_bytes: bytes) -> np.ndarray:
    """解码 JPEG 帧为 BGR ndarray"""
    arr = np.frombuffer(frame_bytes, dtype=np.uint8)
    img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    if img is None:
        raise ValueError("无效的图像数据")
    return img


def preprocess(image: np.ndarray, target_w: int = 640, target_h: int = 480) -> np.ndarray:
    """resize + normalize + CHW + batch维度"""
    resized = cv2.resize(image, (target_w, target_h))
    rgb = cv2.cvtColor(resized, cv2.COLOR_BGR2RGB)
    normalized = rgb.astype(np.float32) / 255.0
    chw = np.transpose(normalized, (2, 0, 1))
    return np.expand_dims(chw, axis=0)
