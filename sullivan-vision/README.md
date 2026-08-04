# Sullivan Vision — 离线模型工程

全景分割模型训练、ONNX 导出、量化与评测。

## 目录结构

| 目录 | 用途 |
|---|---|
| `train/` | PyTorch 训练脚本 (Ultralytics YOLOv8-seg) |
| `export/` | ONNX 导出 + FP16 量化 + checksum |
| `evaluate/` | 评测脚本（六类目标: 人/车/台阶/路障/坡道/柱子） |
| `data/` | 数据集配置与预处理 |
| `configs/` | 模型与训练配置 |
| `notebooks/` | Jupyter 实验记录 |
| `scripts/` | 批量处理工具 |

## 基准任务 (M1)

三组对比数据:
1. FP16 vs FP32 — 耗时差与掩码质量
2. YOLOv8n-seg vs YOLOv8s-seg — 精度与速度取舍
3. 640×480 vs 800×600 — 小目标召回

产物: ONNX 文件 + checksum_sha256 + 评测报告 → 登记到 model_version 表
