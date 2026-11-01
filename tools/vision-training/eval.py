"""评估训练结果——**不看 mAP 就下结论是不行的，只看 mAP 就下结论更不行**。

这个脚本给三组数：

1. `model.val` 的标准指标（mAP50、mAP50-95、每类 P/R）。
2. **小目标召回率**：只统计归一化高度 ≤ `--hazard-height`（默认 0.06，与端侧
   `ObstacleAdvisor.TINY_HEIGHT_RATIO` 同一个数）的标注框，看有多少被检出。
   mAP 对这些"几十像素的障碍"几乎不敏感——它们数量多、贡献小，把 mAP 拉高的是
   大目标。而受护人摔倒时撞上的绝大多数是小目标。
3. **置信度扫描**：同一份权重在 0.28 / 0.35 / 0.50 下的召回与误报数。端侧把阈值从
   0.35 降到 0.28，这个扫描就是那个决定的数据依据；换数据集时要重新跑一遍。

用法：
    python eval.py --weights runs/sullivan-hazard/weights/best.pt --data dataset.yaml
    python eval.py --weights best.pt --data dataset.yaml --conf-sweep 0.2,0.28,0.35,0.5
"""

from __future__ import annotations

import argparse
from pathlib import Path

import yaml


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="评估障碍检测模型（含小目标召回）")
    parser.add_argument("--weights", type=Path, required=True, help="best.pt 路径")
    parser.add_argument("--data", type=Path, default=Path("dataset.yaml"))
    parser.add_argument("--imgsz", type=int, default=640, help="评估输入边长（与端侧一致用 640）")
    parser.add_argument("--batch", type=int, default=16)
    parser.add_argument("--device", type=str, default="0")
    parser.add_argument("--split", type=str, default="val", choices=["val", "test"])
    parser.add_argument("--hazard-height", type=float, default=0.06,
                        help="小目标的归一化高度上限（默认 0.06，对齐端侧 TINY_HEIGHT_RATIO）")
    parser.add_argument("--iou", type=float, default=0.5, help="匹配用的 IoU 阈值")
    parser.add_argument("--conf-sweep", type=str, default="0.28,0.35,0.50",
                        help="逗号分隔的置信度列表")
    parser.add_argument("--limit", type=int, default=0, help="只评估前 N 张（调试用，0 = 全部）")
    return parser.parse_args()


def load_split_paths(data_file: Path, split: str) -> tuple[Path, list[Path], list[Path]]:
    """解析 dataset.yaml，返回 (数据集根目录, 图片列表, 标签列表)。"""
    with data_file.open(encoding="utf-8") as handle:
        config = yaml.safe_load(handle)
    root = Path(config.get("path", ".")).expanduser()
    if not root.is_absolute():
        root = (data_file.parent / root).resolve()
    split_value = config[split]
    if isinstance(split_value, list):
        raise SystemExit("本脚本只支持单一目录的 split，请把 val/test 写成字符串路径")
    images: list[Path] = []
    labels: list[Path] = []
    images_dir = root / split_value
    # 标签目录 = 把首段 "images" 换成 "labels"。用相对路径算，子目录不会被拍平。
    relative_prefix = Path(*Path(split_value).parts[1:]) if "images" in Path(split_value).parts else Path("")
    for image in sorted(images_dir.rglob("*")):
        if image.suffix.lower() not in {".jpg", ".jpeg", ".png", ".bmp", ".webp"}:
            continue
        relative = image.relative_to(images_dir)
        images.append(image)
        labels.append(root / "labels" / relative_prefix / relative.with_suffix(".txt"))
    return root, images, labels


def read_boxes(label_path: Path) -> list[tuple[int, float, float, float, float]]:
    boxes = []
    if not label_path.is_file():
        return boxes
    for line in label_path.read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if len(parts) != 5:
            continue
        boxes.append((int(parts[0]), *(float(value) for value in parts[1:])))
    return boxes


def iou(a: tuple[float, float, float, float], b: tuple[float, float, float, float]) -> float:
    ax1, ay1, ax2, ay2 = a
    bx1, by1, bx2, by2 = b
    inter_w = max(0.0, min(ax2, bx2) - max(ax1, bx1))
    inter_h = max(0.0, min(ay2, by2) - max(ay1, by1))
    inter = inter_w * inter_h
    if inter <= 0:
        return 0.0
    area_a = max(0.0, ax2 - ax1) * max(0.0, ay2 - ay1)
    area_b = max(0.0, bx2 - bx1) * max(0.0, by2 - by1)
    union = area_a + area_b - inter
    return inter / union if union > 0 else 0.0


def to_corners(box: tuple[float, float, float, float]) -> tuple[float, float, float, float]:
    cx, cy, w, h = box
    return cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2


def hazard_recall(model, images, labels, conf, hazard_height, iou_threshold, limit,
                  imgsz) -> dict:
    """小目标召回：只看高度 ≤ hazard_height 的标注框。"""
    total = 0
    hit = 0
    predictions = 0
    pairs = list(zip(images, labels))
    if limit:
        pairs = pairs[:limit]
    for image_path, label_path in pairs:
        ground_truth = [box for box in read_boxes(label_path) if box[4] <= hazard_height]
        results = model.predict(source=str(image_path), conf=conf, verbose=False, imgsz=imgsz)
        detections = []
        for result in results:
            height_px, width_px = result.orig_shape
            for box in result.boxes:
                x1, y1, x2, y2 = (float(v) for v in box.xyxy[0].tolist())
                detections.append((
                    int(box.cls[0].item()),
                    (x1 + x2) / 2 / width_px,
                    (y1 + y2) / 2 / height_px,
                    (x2 - x1) / width_px,
                    (y2 - y1) / height_px,
                ))
        predictions += len(detections)
        used = [False] * len(detections)
        for cls, cx, cy, w, h in ground_truth:
            total += 1
            gt = to_corners((cx, cy, w, h))
            best_index, best_iou = -1, iou_threshold
            for index, det in enumerate(detections):
                if used[index] or det[0] != cls:
                    continue
                score = iou(gt, to_corners(det[1:]))
                if score >= best_iou:
                    best_index, best_iou = index, score
            if best_index >= 0:
                used[best_index] = True
                hit += 1
    return {
        "confidence": conf,
        "ground_truth": total,
        "recalled": hit,
        "recall": hit / total if total else float("nan"),
        "predictions": predictions,
        "predictions_per_image": predictions / max(1, len(pairs)),
    }


def main() -> int:
    args = parse_args()

    from ultralytics import YOLO

    model = YOLO(str(args.weights))

    print("=" * 68)
    print("标准指标（model.val）")
    print("=" * 68)
    metrics = model.val(data=str(args.data), imgsz=args.imgsz, batch=args.batch,
                        device=args.device, split=args.split, plots=True, verbose=False)
    box = metrics.box
    print(f"mAP50    : {box.map50:.4f}")
    print(f"mAP50-95 : {box.map:.4f}")
    print(f"precision: {box.mp:.4f}   recall: {box.mr:.4f}")
    print("\n每类（class, AP50, AP50-95）：")
    for index, class_id in enumerate(box.ap_class_index):
        name = metrics.names[int(class_id)]
        print(f"  {name:<18} {box.ap50[index]:.4f}  {box.ap[index]:.4f}")

    # 自建的召回扫描：需要能定位到 val/test 目录，读不到就跳过而不是报错。
    try:
        _, images, labels = load_split_paths(args.data, args.split)
    except (KeyError, FileNotFoundError) as error:
        print(f"\n[skip] 无法定位 {args.split} 目录（{error}），跳过小目标召回与置信度扫描")
        return 0

    if not images:
        print(f"\n[skip] {args.split} 目录里没有图片，跳过小目标召回")
        return 0

    print("\n" + "=" * 68)
    print(f"小目标召回（归一化高度 ≤ {args.hazard_height}）")
    print("=" * 68)
    confidences = [float(value) for value in args.conf_sweep.split(",") if value.strip()]
    print(f"{'conf':>6} {'GT':>6} {'recalled':>9} {'recall':>7} {'preds/img':>10}")
    for conf in confidences:
        stats = hazard_recall(model, images, labels, conf, args.hazard_height,
                              args.iou, args.limit, args.imgsz)
        print(f"{stats['confidence']:>6.2f} {stats['ground_truth']:>6d} "
              f"{stats['recalled']:>9d} {stats['recall']:>7.3f} "
              f"{stats['predictions_per_image']:>10.1f}")

    print("\n判读方式：")
    print("  - recall 随 conf 上升而下降，preds/img 也下降。选点就是在这两个方向之间取平衡：")
    print("    端侧现在取 0.28（偏 recall），因为漏报的代价是摔倒，多报的代价是多听一句。")
    print("  - 若 0.28 的 preds/img 明显高于 0.35 而 recall 没涨，说明降阈值只是在放大噪声，")
    print("    应该回到 0.35 并用 train.py 的 copy_paste / imgsz 提高小目标本身的质量。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
