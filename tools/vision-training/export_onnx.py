"""把训练好的 .pt 导出成受护人端能直接用的 ONNX。

端侧 `Yolo26Detector` 的契约（见 `client-protectee/.../vision/detect/Yolo26Detector.java`）：

* 输入：`[1, 3, 640, 640]`，RGB，0–1，letterbox 填充到方图；
* 输出：两个张量 `logits [1, N, C]` 与 `pred_boxes [1, N, 4]`，**NMS 已由模型内部完成**。

所以导出必须带 `nms=True`（end2end）。导成 YOLOv8 那种 `[1, 84, 8400]` 单张量，
App 会直接抛 `IllegalStateException`。导出后自动调用 `check_export.py` 校验，
不通过就返回非零退出码——不要手工"看起来差不多"地拷进 assets。

用法：
    python export_onnx.py --weights runs/sullivan-hazard/weights/best.pt
    python export_onnx.py --weights best.pt --imgsz 960 --half
"""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path

import check_export


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="导出受护人端可用的 YOLO26 ONNX")
    parser.add_argument("--weights", type=Path, required=True, help=".pt 权重")
    parser.add_argument("--imgsz", type=int, default=640, help="导出输入边长（默认 640）")
    parser.add_argument("--opset", type=int, default=12, help="ONNX opset（默认 12，兼容性好）")
    parser.add_argument("--half", action="store_true", help="FP16 导出；仅当确认端侧走 GPU 时使用")
    parser.add_argument("--simplify", action="store_true", default=True, help="onnx-simplifier 化简")
    parser.add_argument("--copy-to", type=Path, default=None,
                        help="导出后拷贝到指定路径，例如 app/src/main/assets/yolo26n.onnx")
    parser.add_argument("--skip-check", action="store_true", help="跳过契约校验（不建议）")
    return parser.parse_args()


def main() -> int:
    args = parse_args()

    from ultralytics import YOLO

    if not args.weights.is_file():
        print(f"[ERROR] 找不到权重：{args.weights}")
        return 2

    model = YOLO(str(args.weights))
    # nms=True 是关键：它让导出图自带 end2end 的 NMS，输出才是 (logits, pred_boxes)。
    exported = model.export(
        format="onnx",
        imgsz=args.imgsz,
        opset=args.opset,
        half=args.half,
        simplify=args.simplify,
        nms=True,
        dynamic=False,
    )
    onnx_path = Path(exported)
    print(f"\n已导出：{onnx_path}")
    print(f"体积  ：{onnx_path.stat().st_size / (1 << 20):.2f} MB")

    status = 0
    if not args.skip_check:
        print("\n校验端侧契约：")
        status = check_export.check(onnx_path, args.imgsz)
        if status != 0:
            print("\n[STOP] 导出的模型不符合端侧契约，不要拷进 assets。")
            return status

    if args.copy_to:
        args.copy_to.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(onnx_path, args.copy_to)
        print(f"\n已拷贝到：{args.copy_to}")

    print("\n后续步骤：")
    print("  1. 若换了类别表，同步修改 CocoLabels 与 dataset.yaml，两边必须逐类对应；")
    print("  2. 装到真机上跑一遍夜路与台阶场景，观察 logcat 的 'scene {...}' 回放；")
    print("  3. 把导出的 sha256 记进文档，便于确认设备上跑的是哪一版权重。")
    return status


if __name__ == "__main__":
    raise SystemExit(main())
