把 YOLO 模型放在这个目录下。

默认文件名：yolov8n.onnx

⚠️ 模型文件**不入库**。仓库根 .gitignore 有一条全库规则 `*.onnx`
（"模型产物体积大，不入库；用 MinIO 或 Release 分发"），所以新克隆的仓库里
这个目录只有本说明文件，**没有模型**。首次构建运行前需要自己补上，
否则应用会走「未找到模型」分支（界面顶部提示，可点右上角「更换模型」手动导入）。

生成方式（需要 Python 环境）：

    pip install ultralytics
    yolo export model=yolov8n.pt format=onnx imgsz=640 opset=12

执行完会在当前目录得到 yolov8n.onnx（约 12 MB），把它复制到这里即可。

也可以放其他 ultralytics 导出的检测模型（yolo11n.onnx、yolov8s.onnx 等），
但需要相应修改 ModelStore.ASSET_NAME；或者不改代码，直接在 App 里点右上角
「更换模型」从手机存储中选择 .onnx 文件。

如果用的是自训练模型，可以在本目录再放一个 classes.txt，每行一个类别名，
顺序与训练时一致；不放则按 COCO 80 类处理。

模型对应的代码位置：com.sullivan.protectee.vision.detect.ModelStore
