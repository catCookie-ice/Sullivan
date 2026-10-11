# ONNX Runtime 的 Java API 由 native 侧按名字回调（OnnxValue / OrtSession.Result 等），
# 混淆后 native 找不到类会直接崩在加载模型这一步，所以整包保留。
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }

# 移植进来的感知/决策代码（com.sullivan.android.vision.**）不含任何反射，
# 不需要额外 keep 规则。
