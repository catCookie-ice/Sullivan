# 原先这里有一条 `-keep class ai.onnxruntime.**`，那是为受护人端的端侧障碍检测
# （vision/** + onnxruntime-android）留的：native 侧按名字回调 Java 类，
# 混淆后找不到类会直接崩在加载模型那一步。
# 该功能已随受护人端拆到 client-android/client-protectee/，本端不再依赖 ONNX，
# 规则随之删除 —— 留着它只会让 R8 白保留一个包。

# ---------------------------------------------------------------------------
# 网络 DTO
#
# Gson 用反射按**字段名**读写 JSON，字段名被 R8 改成 a/b/c 之后，
# 服务端返回的 accessToken 就映射不到任何东西，表现为「登录成功但字段全是 null」——
# 这类故障在 debug 里永远复现不出来（debug 不混淆），只在 release 上出现。
# DTO 只有字段没有方法，整包保留的代价可以忽略。
-keep class com.sullivan.android.data.api.dto.** { *; }

# Gson 自身：泛型签名与注解在混淆后仍需可用。
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-dontwarn sun.misc.**
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# ---------------------------------------------------------------------------
# ZXing
#
# 解码走的是反射最少的路径，但 EncodeHintType / DecodeHintType 是枚举 Map，
# 且 MultiFormatReader 会按类名加载若干 Reader 实现。释放构建至今没有在真机上
# 跑过（见 17-开发日记-2026-10-16.md 的已知风险），这里按保守口径整包保留。
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# OkHttp：平台相关实现类是可选的，缺失时它自己有回退路径。
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
