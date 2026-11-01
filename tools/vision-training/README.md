# 障碍检测训练流水线

这套脚本训练的是**受护人端（`client-protectee`）运行的那个检测模型**：
`app/src/main/assets/yolo26n.onnx`。它们不在任何服务的启动路径上，App 运行时
只需要导出的 `.onnx`，不需要 Python。

设计与取舍见 [23-障碍识别准确性与训练方案](../../docs/final/23-障碍识别准确性与训练方案.md)，
改动的来龙去脉见 [17-开发日记-2026-11-01](../../docs/final/17-开发日记-2026-11-01-障碍预警与碰撞预测.md)。

## 1. 为什么需要自己训练

COCO 的 80 类是为"图片里有什么"设计的，不是为"什么东西会把我绊倒"设计的。
三类缺口：

1. **有类别但没有贴合场景的样本**：COCO 里的 `person`/`bicycle` 大多是中景、
   白天、正面；人行道上的障碍是贴地的低视角、逆光、被截断的。
2. **没有类别**：台阶、路缘石、坑洼、施工围挡在 COCO 里根本不存在，而这是
   失明用户摔倒的主要原因。
3. **有类别但不需要**：`cup`/`book`/`remote` 这些被误检出来只会制造噪声。

所以策略是：**COCO 预训练 → 自建数据微调 → 类别表收敛到"会绊倒人的东西"**。

## 2. 数据准备

`dataset.yaml` 就是类别表本身。目录结构（ultralytics 约定）：

```
datasets/sullivan-hazard/
├── images/train/  *.jpg      ├── labels/train/  *.txt
├── images/val/    *.jpg      ├── labels/val/    *.txt
└── images/test/   *.jpg      └── labels/test/   *.txt
```

标签每行 `class cx cy w h`，全部归一化到 0–1。

**三条硬规则**（违反其中任何一条，评估结果都不可信）：

1. 同一个人/地点/拍摄日的图片只能出现在一个 split 里。用随机切分会让"同一条街的
   相邻帧"同时出现在训练和验证里，mAP 虚高十几个点。
2. `test` 只用来出最终数字，不要在调参时看它。看多了它就不再是测试集。
3. 每类至少 200 个实例，`step`/`curb`/`bollard` 这三类别低于 500——它们是本次
   改造专门要补的短板，样本不够时先补数据再谈模型。

数据来源见 `23-障碍识别准确性与训练方案.md` §4 的链接表。Roboflow 的社区数据集
许可证各不相同，**商用前逐个核对**，本项目只把它们当训练素材。

## 3. 四步跑完

```bash
pip install -r requirements.txt

# 1) 微调。默认 imgsz=960（训练看得清小目标），导出时再回到 640。
python train.py --data dataset.yaml --epochs 120

# 2) 评估。除了 mAP，还会给小目标召回与置信度扫描——那才是端侧阈值 0.28 的依据。
python eval.py --weights runs/sullivan-hazard/weights/best.pt --data dataset.yaml

# 3) 导出成端侧契约要求的 end2end ONNX，并在同一步里校验形状。
python export_onnx.py --weights runs/sullivan-hazard/weights/best.pt --imgsz 640 \
    --copy-to ../../client-android/client-protectee/app/src/main/assets/yolo26n.onnx

# 4) 单独复核任意一份已在仓库里的模型（含深度模型）
python check_export.py ../../client-android/client-protectee/app/src/main/assets/yolo26n.onnx
python check_export.py ../../client-android/client-protectee/app/src/main/assets/yolo26n-depth.onnx \
    --imgsz 768 --kind depth
```

第 3 步的 `--copy-to` 之前，建议先看一眼 `[OK]`：**导出成功不等于能装进 App**，
中间隔着输入尺寸、输出个数、NMS 是否已在图内。

## 4. 端侧契约（改模型前必读）

`Yolo26Detector` 只认这一种导出：

| 项 | 要求 | 现状（仓库内资产实测） |
|---|---|---|
| 输入 | `[N, 3, 640, 640]` float32，RGB 0–1，letterbox | `pixel_values [None,3,640,640]` |
| 输出 1 | `logits [N, 300, 80]`，**sigmoid 之前的 logits** | `logits [None,300,80]` |
| 输出 2 | `pred_boxes [N, 300, 4]`，归一化 `cx,cy,w,h` | `pred_boxes [None,300,4]` |
| NMS | 已在图内完成（300 = `max_det`） | 是 |

仓库内资产实测：

```
yolo26n.onnx       9.43 MB   sha256 cda08d9440217e243e075ee839f40383c59b3f973e493f9f6c7452922a69436e
yolo26n-depth.onnx 19.81 MB  sha256 6583ec12521a7e75eacb209db63393158c7e0e772cda9fdad8c8c0fe74af2a1e
                        input images[1,3,768,768] → output depth[1,1,768,768]
```

换模型时这两条会同时失效，必须一起改：

1. `CocoLabels.COCO_80` 的顺序必须与训练时的 `names` **逐项对齐**——端侧按索引取
   类别名，错一位就会把"电线杆"念成"长椅"。
2. `dataset.yaml` 的类别数若变了，`logits` 的最后一维跟着变，`Yolo26Detector`
   读的是张量形状而不是常量，能自适应；但 `ObstacleAdvisor` 的位置/紧急度判据
   是按"人/车/杆"这类语义调的，加类别后要重新过一遍 `ObstacleAdvisorTest`。

## 5. 训练完还要验证什么

自动化指标之外，必须在真机上跑这三件事，否则不能算"准确性提高了"：

1. **夜路**：开灯 + 逆光，看是否还认得出路缘石。
2. **台阶**：下行台阶是最危险的漏检，必须亲眼看播报是否在两秒前出现。
3. **误报密度**：站在人行道上不动 60 秒，数播报次数。目标是 < 3 次/分钟；
   超过这个数，用户会直接关掉语音。

`logcat` 里每帧都会打一行 `scene {...}`（`SceneSnapshot` 的 JSON），可以拿这些
行做回放分析，不用重新跑推理。
