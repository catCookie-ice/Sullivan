"""校验一份 ONNX 检测模型是否符合受护人端的输入/输出契约。

为什么单独写这个脚本：端侧 `Yolo26Detector` 只认 **YOLO26 end2end 导出**
（两个输出 `logits [1,N,C]` + `pred_boxes [1,N,4]`），收到 YOLOv8 时代的
`[1,84,8400]` 单张量会直接抛异常。而"导出成功"与"能装进 App"是两件事——
中间隔着输入尺寸、输出个数、NMS 是否已经做掉。这个脚本把那段距离量化为一次检查，
放在训练流水线的最后一步，避免把时间浪费在"装上去才发现形状不对"。

用法：
    python check_export.py path/to/yolo26n.onnx
    python check_export.py path/to/best.onnx --imgsz 960
"""

from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path

import onnx
from onnx import TensorProto

# 端侧 `Yolo26Detector.DEFAULT_INPUT_SIZE`
DEFAULT_INPUT_SIZE = 640


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def dims_of(value_info) -> list:
    """取出张量各维。动态维（导出成 `batch_size`/`N` 这类名字）返回 None。"""
    shape = value_info.type.tensor_type.shape
    dims = []
    for dim in shape.dim:
        dims.append(dim.dim_value if dim.HasField("dim_value") else None)
    return dims


def describe(label: str, value_info) -> str:
    return f"{label}: {value_info.name} {dims_of(value_info)}"


def check(model_path: Path, expect_imgsz: int, kind: str = "detect") -> int:
    if not model_path.is_file():
        print(f"[FAIL] 文件不存在：{model_path}")
        return 2

    size_mb = model_path.stat().st_size / (1 << 20)
    print(f"模型    : {model_path}")
    print(f"体积    : {size_mb:.2f} MB")
    print(f"sha256  : {sha256_of(model_path)}")

    model = onnx.load(str(model_path))
    graph = model.graph

    inputs = list(graph.input)
    outputs = list(graph.output)
    print("输入    :")
    for value_info in inputs:
        print("  " + describe("in ", value_info))
    print("输出    :")
    for value_info in outputs:
        print("  " + describe("out", value_info))

    problems: list[str] = []

    if len(inputs) != 1:
        problems.append(f"输入数量应为 1，实际 {len(inputs)}")
    else:
        dims = dims_of(inputs[0])
        if len(dims) != 4 or dims[1] != 3:
            problems.append(f"输入应为 NCHW 且通道为 3，实际 {dims}")
        elif dims[2] is not None and dims[3] is not None and dims[2] != dims[3]:
            problems.append(f"输入应为正方形，实际 {dims[2]}x{dims[3]}")
        elif dims[2] not in (None, expect_imgsz):
            problems.append(
                f"输入边长应为 {expect_imgsz}（或动态维），实际 {dims[2]}；"
                f"端侧按固定边长预处理，尺寸不一致会得到错误的尺度"
            )

    if kind == "depth":
        # 深度模型的契约完全不同：单输入、单输出、输出是逐像素的米数。
        # 单独一条路径，才能用它验证第二份资产而不误报。
        if len(outputs) != 1:
            problems.append(f"深度模型应只有一个输出，实际 {len(outputs)}")
        else:
            dims = dims_of(outputs[0])
            if len(dims) != 4 or dims[1] != 1:
                problems.append(f"深度输出应为 [1,1,H,W]，实际 {dims}")
            elif dims[2] not in (None, expect_imgsz):
                problems.append(f"深度输出边长应为 {expect_imgsz}，实际 {dims[2]}")
    elif len(outputs) != 2:
        problems.append(
            f"输出数量应为 2（logits + pred_boxes），实际 {len(outputs)}；"
            f"YOLOv8 的 [1,84,8400] 单张量不被端侧支持"
        )
    else:
        shapes = [dims_of(value_info) for value_info in outputs]
        # 靠"最后一维是 4"区分两类输出：先找框，剩下的那个才是 logits。
        # **不能**假设 logits 的最后一维比 N 大——COCO 80 类时 80 < 300。
        boxes = next((s for s in shapes if len(s) == 3 and s[2] == 4), None)
        logits = next((s for s in shapes if s is not boxes and len(s) == 3), None)
        if boxes is None:
            problems.append(f"未找到 pred_boxes [N,4] 输出，实际 {shapes}")
        if logits is None:
            problems.append(f"未找到 logits [N,C] 输出，实际 {shapes}")
        if boxes is not None and logits is not None:
            if boxes[1] is not None and logits[1] is not None and boxes[1] != logits[1]:
                problems.append(f"两个输出的槽位数不一致：{logits[1]} vs {boxes[1]}")
            if logits[2] == 4:
                problems.append("logits 的最后一维是 4，看起来仍是原始 [cx,cy,w,h] 布局")

    # 数据类型：端侧按 float32 读缓冲。
    for value_info in inputs:
        dtype = value_info.type.tensor_type.elem_type
        if dtype != TensorProto.FLOAT:
            name = TensorProto.DataType.Name(dtype)
            problems.append(f"输入 {value_info.name} 不是 float32（{name}）；端侧按 float32 读")

    if problems:
        print("\n[FAIL] 与端侧契约不符：")
        for problem in problems:
            print(f"  - {problem}")
        return 1

    target = "Yolo26Detector（检测）" if kind == "detect" else "DepthEstimator（深度）"
    print(f"\n[OK] 符合 {target} 的输入/输出契约，可直接放入 app/src/main/assets/")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="校验 ONNX 模型是否符合受护人端契约")
    parser.add_argument("model", type=Path, help=".onnx 文件路径")
    parser.add_argument("--imgsz", type=int, default=DEFAULT_INPUT_SIZE,
                        help=f"期望的输入边长（检测默认 {DEFAULT_INPUT_SIZE}，深度模型请传 768）")
    parser.add_argument("--kind", choices=["detect", "depth"], default="detect",
                        help="detect = 障碍检测模型（默认）；depth = 单目深度模型")
    args = parser.parse_args()
    return check(args.model, args.imgsz, args.kind)


if __name__ == "__main__":
    sys.exit(main())
