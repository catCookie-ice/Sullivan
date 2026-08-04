"""M1 模型基准：三组对比数据"""
import time
import numpy as np
import onnxruntime as ort


def benchmark_onnx(model_path: str, resolution: tuple = (640, 480),
                   num_runs: int = 100, warmup: int = 10) -> dict:
    """测量 ONNX 模型推理延迟"""
    session = ort.InferenceSession(model_path, providers=["CUDAExecutionProvider"])
    dummy = np.random.randn(1, 3, resolution[1], resolution[0]).astype(np.float32)

    # Warmup
    for _ in range(warmup):
        session.run(None, {"input": dummy})

    # Benchmark
    latencies = []
    for _ in range(num_runs):
        start = time.perf_counter()
        session.run(None, {"input": dummy})
        latencies.append((time.perf_counter() - start) * 1000)

    latencies.sort()
    return {
        "model": model_path,
        "resolution": f"{resolution[0]}x{resolution[1]}",
        "p50_ms": np.median(latencies),
        "p95_ms": latencies[int(len(latencies) * 0.95)],
        "p99_ms": latencies[int(len(latencies) * 0.99)],
        "avg_ms": np.mean(latencies),
        "runs": num_runs,
    }


if __name__ == "__main__":
    result = benchmark_onnx("models/yolov8n-seg.onnx")
    print(result)
