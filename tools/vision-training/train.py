"""在自建"人行道障碍"数据集上微调 YOLO26。

这不是一个通用训练脚本，它把**受护人端的需求**写进了默认参数：

1. **小目标优先**：默认 `imgsz=960`（端侧推理仍是 640，训练时用更大的输入让网络
   学到小目标特征，导出时再回到 640/960 由 `export_onnx.py` 决定）。同时打开
   `copy_paste`——把小障碍复制粘贴到别的画面上，是唯一不花钱就能增加样本的增强。
2. **不翻转上下**：`fliplr=0.5` 但 `flipud=0.0`。人行道场景上下颠倒的图像不存在，
   开了只会教模型"天空可以在地上"。
3. **色彩扰动偏保守**：`hsv_v` 抬高一点（模拟逆光与树荫），`hsv_h` 压低（衣服颜色不该
   影响"这是不是障碍"）。
4. **贴身视角**：`perspective` 与 `degrees` 小幅度打开，因为手机挂在胸前/手持，
   俯仰角每帧都在变。

用法：
    python train.py --data dataset.yaml                      # 从 yolo26n.pt 微调
    python train.py --data dataset.yaml --imgsz 960 --epochs 120
    python train.py --data dataset.yaml --resume             # 断点续训

训练完成后最优权重在 `runs/<name>/weights/best.pt`，接着跑 `eval.py` 与
`export_onnx.py`。
"""

from __future__ import annotations

import argparse
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="微调 YOLO26 用于人行道障碍检测")
    parser.add_argument("--data", type=Path, default=Path("dataset.yaml"),
                        help="数据集配置（默认 dataset.yaml）")
    parser.add_argument("--model", type=str, default="yolo26n.pt",
                        help="初始权重。yolo26n.pt 从 COCO 预训练开始；也可传自己的 best.pt 续训")
    parser.add_argument("--imgsz", type=int, default=960, help="训练输入边长（默认 960）")
    parser.add_argument("--epochs", type=int, default=120, help="训练轮数")
    parser.add_argument("--batch", type=int, default=16, help="批大小；显存不足就降到 8")
    parser.add_argument("--device", type=str, default="0", help="'0' 为第一块 GPU，'cpu' 为 CPU")
    parser.add_argument("--workers", type=int, default=8, help="数据加载线程数")
    parser.add_argument("--patience", type=int, default=30, help="验证集不再改善多少轮后早停")
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--project", type=Path, default=Path("runs"),
                        help="输出目录（默认 runs/）")
    parser.add_argument("--name", type=str, default="sullivan-hazard",
                        help="本次运行的目录名")
    parser.add_argument("--resume", action="store_true", help="从上次中断处继续")
    return parser.parse_args()


def main() -> int:
    args = parse_args()

    # ultralytics 是重依赖，放在函数里导入：--help 与参数检查不需要等它加载。
    from ultralytics import YOLO

    if not args.data.is_file():
        print(f"[ERROR] 找不到数据集配置：{args.data}")
        return 2

    model = YOLO(args.model)

    train_kwargs = {
        "data": str(args.data),
        "imgsz": args.imgsz,
        "epochs": args.epochs,
        "batch": args.batch,
        "device": args.device,
        "workers": args.workers,
        "patience": args.patience,
        "seed": args.seed,
        "project": str(args.project),
        "name": args.name,
        "exist_ok": True,
        "resume": args.resume,
        # —— 与"绊倒人的小障碍"直接相关的增强 ——
        # mosaic 把四张图拼成一张，等于在同样的显存下见到更多小目标；最后 20 轮关掉，
        # 让模型在真实布局上收尾（mosaic 的拼接缝在真实画面里不存在）。
        "mosaic": 0.8,
        "close_mosaic": 20,
        # 缩放与平移：模拟"障碍不在画面正中""用户走近/走远"。
        "scale": 0.5,
        "translate": 0.2,
        # copy_paste 专门补小目标：把已标注的障碍抠出来贴到别的场景里。
        "copy_paste": 0.3,
        # 上下不翻转，左右可以翻（人行道左右对称）。
        "fliplr": 0.5,
        "flipud": 0.0,
        # 手持设备的俯仰角变化。
        "degrees": 8.0,
        "perspective": 0.0005,
        # 颜色：亮度大幅抖动（树荫/逆光/夜间），色相轻微（避免"红衣服=障碍"）。
        "hsv_h": 0.010,
        "hsv_s": 0.5,
        "hsv_v": 0.5,
        # 优化器：小数据集上用低学习率微调，别把 COCO 学到的先验冲掉。
        "optimizer": "AdamW",
        "lr0": 0.001,
        "lrf": 0.01,
        "cos_lr": True,
        "warmup_epochs": 3.0,
        # 端侧部署的约束：类别不均衡时用焦点式损失的默认设置即可，这里只开类别权重。
        "cls": 0.5,
        "plots": True,
        "val": True,
    }

    print("训练参数：")
    for key, value in train_kwargs.items():
        print(f"  {key} = {value}")

    results = model.train(**train_kwargs)

    best = Path(results.save_dir) / "weights" / "best.pt"
    print("\n训练结束。")
    print(f"  最优权重：{best}")
    print("下一步：")
    print(f"  python eval.py --weights {best} --data {args.data}")
    print(f"  python export_onnx.py --weights {best} --imgsz 640")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
