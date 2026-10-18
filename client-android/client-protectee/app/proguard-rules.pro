# ONNX Runtime 的 Java API 由 native 侧按名字回调（OnnxValue / OrtSession.Result 等），
# 混淆后 native 找不到类会直接崩在加载模型这一步，所以整包保留。
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }

# 移植进来的感知/决策代码（com.sullivan.protectee.vision.**）不含任何反射，
# 不需要额外 keep 规则。

# ---------------------------------------------------------------------------
# 网络 DTO
#
# Gson 用反射按**字段名**读写 JSON，字段名被 R8 改成 a/b/c 之后，
# 服务端返回的 accessToken 就映射不到任何东西，表现为「登录成功但字段全是 null」——
# 这类故障在 debug 里永远复现不出来（debug 不混淆），只在 release 上出现。
# DTO 只有字段没有方法，整包保留的代价可以忽略。
#
# 注意这条规则是**按包名**生效的：新 DTO 必须放进 data/api/dto，别挪到别处。
-keep class com.sullivan.protectee.data.api.dto.** { *; }

# Gson 自身：泛型签名与注解在混淆后仍需可用。
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-dontwarn sun.misc.**
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# ---------------------------------------------------------------------------
# ZXing
#
# 本端只用到生成侧（MultiFormatWriter / BitMatrix），几乎不走反射；
# 但按保守口径整包保留，与 client-guardian 保持同一套规则，避免「两边看起来一样、
# 行为不一样」的隐藏差异。
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# OkHttp：平台相关实现类是可选的，缺失时它自己有回退路径。
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
