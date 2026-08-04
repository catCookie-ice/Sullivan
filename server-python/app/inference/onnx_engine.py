"""ONNX Runtime CUDA EP 推理引擎 (docs/final/13 §10.1)"""
import numpy as np
import onnxruntime as ort
from app.inference.backend import InferenceBackend, InferenceResult


class ONNXInferenceEngine(InferenceBackend):
    """ONNX Runtime 实现 — 默认 CUDA EP + FP16"""

    def __init__(self, model_path: str, device: str = "cuda", precision: str = "fp16"):
        self.model_path = model_path
        self.device = device
        self.precision = precision
        self.session = None

    def load_model(self, model_path: str):
        providers = ["CUDAExecutionProvider", "CPUExecutionProvider"] \
            if self.device == "cuda" else ["CPUExecutionProvider"]
        self.session = ort.InferenceSession(model_path, providers=providers)

    def infer(self, image: np.ndarray) -> InferenceResult:
        # TODO(M3): 前处理 → ONNX 推理 → 后处理 → 返回 masks/boxes/scores
        raise NotImplementedError("M3 实现")

    def warmup(self):
        if self.session:
            dummy = np.random.randn(1, 3, 640, 480).astype(np.float32)
            self.session.run(None, {"input": dummy})
