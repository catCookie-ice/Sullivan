"""推理后端策略接口 (docs/final/13 §4)"""
from abc import ABC, abstractmethod
import numpy as np


class InferenceResult:
    """推理结果"""
    def __init__(self, masks: np.ndarray, boxes: list, scores: list, class_ids: list):
        self.masks = masks
        self.boxes = boxes
        self.scores = scores
        self.class_ids = class_ids


class InferenceBackend(ABC):
    """推理后端抽象 — MINI 实现为 ONNX Runtime CUDA EP"""

    @abstractmethod
    def load_model(self, model_path: str):
        ...

    @abstractmethod
    def infer(self, image: np.ndarray) -> InferenceResult:
        ...

    @abstractmethod
    def warmup(self):
        """预热推理会话"""
