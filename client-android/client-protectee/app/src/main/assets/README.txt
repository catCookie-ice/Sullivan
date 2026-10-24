把 YOLO 模型放在这个目录下。

Sullivan 守护现在用**两个**模型，各自独立、都可选：

    检测模型：yolo26n.onnx        （必需，约 9–10 MB）
    深度模型：yolo26n-depth.onnx  （可选，约 21 MB）

⚠️ 模型文件**不入库**。仓库根 .gitignore 有一条全库规则 `*.onnx`
（"模型产物体积大，不入库；用 MinIO 或 Release 分发"），所以新克隆的仓库里
这个目录只有本说明文件，**没有模型**。首次构建运行前需要自己补上，
否则应用会走「未找到模型」分支（界面顶部提示，可点击「更换模型」手动导入）。

下载（ultralytics 官方 release，v8.4.0 起提供现成 ONNX）：

    https://github.com/ultralytics/assets/releases/download/v8.4.0/yolo26n.onnx
    https://github.com/ultralytics/assets/releases/download/v8.4.0/yolo26n-depth.onnx

或者自己导出（需要 Python 环境 + ultralytics ≥ 8.4）：

    pip install ultralytics
    yolo export model=yolo26n.pt       format=onnx imgsz=640 opset=12
    yolo export model=yolo26n-depth.pt format=onnx imgsz=768 opset=12

深度用 768：官方 yolo26n-depth.onnx 的输入就是 768×768（实测），
用 640 导出得到的是另一份尺寸，与官方权重不一致。

--- 关于检测模型的输出布局（重要）---

本项目**只支持 YOLO26 的默认导出**（`end2end=True`），输出**两个**张量：

    logits     [1, 300, 80]   原始 logits（不是概率，要过 sigmoid）
    pred_boxes [1, 300, 4]    cx,cy,w,h，归一化到 640×640 输入；**已做过 NMS**

上面那个下载链接拿到的就是这个格式，直接用即可。

**不再兼容 YOLOv8 的 [1,84,8400] 单张量。** 放错模型时应用会明确报错并说明
输出形状不符，而不是给你一个空画面 —— 能立刻看出来，比"能跑但对不上"好得多。

--- 关于深度模型：注意输入尺寸不同 ---

**为什么深度是另一个模型，不是检测的附带输出。**
YOLO26 把深度做成一条独立的任务线（task=depth），权重名带 `-depth` 后缀。

**两个模型的输入尺寸不一样，这是实测出来的：**

    检测 yolo26n.onnx        输入 [1,3,640,640]   输出 logits + pred_boxes
    深度 yolo26n-depth.onnx  输入 [1,3,768,768]   输出 [1,1,768,768] 米数

所以两者**各做各的 letterbox**（不是共用一份预处理张量 —— 拿 640 的张量喂
768 的模型会直接报 Got invalid dimensions）。把检测框换算到深度图像素时，
必须用深度模型自己的 letterbox 参数；用错会让采样点偏移几十个像素。

**没有深度模型会怎样。**
障碍检测完全正常，只是距离退回改造前的几何启发式（框下沿够低或面积够大就算"近"），
播报里不会出现米数。深度模型只是把"较近"变成"1.5 米处"，是精度增强，不是功能前提。

**性能提醒。** 桌面 CPU 实测检测 43 ms、深度 85 ms，合计约 128 ms/帧（约 7.8 FPS
上限）。手机只会更慢，所以深度是**隔帧**跑的，检测仍然每帧都跑。

如果用的是自训练模型，可以在本目录再放一个 classes.txt，每行一个类别名，
顺序与训练时一致；不放则按 COCO 80 类处理。

模型对应的代码位置：
    检测  com.sullivan.protectee.vision.detect.ModelStore / YoloV8Detector
    深度  com.sullivan.protectee.vision.depth.DepthEstimator
    距离  com.sullivan.protectee.vision.depth.DistancePolicy
