"""
YOLOv8-seg → ONNX 导出 + FP16 量化 + checksum

产出:
  - {model_name}_{resolution}_{precision}.onnx
  - checksum_sha256.txt
"""
import hashlib
from ultralytics import YOLO


def export_model(model_name: str = "yolov8n-seg",
                 input_width: int = 640,
                 input_height: int = 480,
                 half: bool = True) -> str:
    """导出 ONNX 模型并计算 checksum"""
    model = YOLO(f"{model_name}.pt")
    onnx_path = model.export(
        format="onnx",
        imgsz=(input_height, input_width),
        half=half,
        opset=18,
    )

    with open(onnx_path, "rb") as f:
        sha256 = hashlib.sha256(f.read()).hexdigest()

    with open("checksum_sha256.txt", "w") as f:
        f.write(f"{sha256}  {onnx_path}")

    print(f"Exported: {onnx_path}")
    print(f"SHA256: {sha256}")
    return onnx_path


if __name__ == "__main__":
    export_model()
