# ============================================================
# LiteRT-LM Backend 插件 ProGuard 规则
# ============================================================
# 本 APK 打包:
#  - Kotlin 类(LiteRTPluginService / LiteRTBinder / LiteRTDescriptor / LiteRTBackend 等)。
#  - AIDL 自动生成的 Stub / Proxy(IPluginInterface.Stub 等)。
#  - libaibox_litert.so(本工程 cpp 编译)+ 后续 CI 注入的 LiteRT-LM / QNN .so。
#  - assets/plugin.json(供宿主快速扫描)。
#
# 关键约束:
#  - 类名 LiteRTPluginService 在 manifest 中以 android:name=".LiteRTPluginService" 引用,不能混淆。
#  - 类名 LiteRTDescriptor 在 manifest meta-data 中以字符串引用,宿主 Class.forName 加载,不能混淆。
#  - AIDL 生成的 Stub / Proxy 跨进程 onTransact 分发表依赖方法 ID,不能被混淆。
#  - native 函数名 `Java_com_aibox_litert_backend_LiteRTBackend_*` 与 Kotlin 类全限定名硬绑定,
#    不能被 R8 改名(否则 JNI 链接失败)。

# --- AIDL 生成的接口与 Stub ---
-keep interface com.aibox.plugin.IPluginInterface { *; }
-keep class com.aibox.plugin.IPluginInterface$Stub { *; }
-keep class com.aibox.plugin.IPluginInterface$Stub$Proxy { *; }
-keep class com.aibox.plugin.IPluginInterface$Default { *; }

-keep interface com.aibox.plugin.IPluginDescriptor { *; }
-keep class com.aibox.plugin.IPluginDescriptor$Stub { *; }
-keep class com.aibox.plugin.IPluginDescriptor$Stub$Proxy { *; }
-keep class com.aibox.plugin.IPluginDescriptor$Default { *; }

-keep interface com.aibox.plugin.IStreamCallback { *; }
-keep class com.aibox.plugin.IStreamCallback$Stub { *; }
-keep class com.aibox.plugin.IStreamCallback$Stub$Proxy { *; }

-keep interface com.aibox.plugin.IPluginLogCallback { *; }
-keep class com.aibox.plugin.IPluginLogCallback$Stub { *; }
-keep class com.aibox.plugin.IPluginLogCallback$Stub$Proxy { *; }

# --- AIDL parcelable:字段名参与 Parcel 序列化,不能被混淆 ---
-keep class com.aibox.plugin.PluginInfo { *; }
-keep class com.aibox.plugin.PluginInfo$* { *; }
-keep class com.aibox.plugin.SettingFieldEntry { *; }
-keep class com.aibox.plugin.SettingFieldEntry$* { *; }
-keep class com.aibox.plugin.BackendCapabilitiesParcel { *; }
-keep class com.aibox.plugin.BackendCapabilitiesParcel$* { *; }
-keep class com.aibox.plugin.LoadModelRequest { *; }
-keep class com.aibox.plugin.LoadModelRequest$* { *; }
-keep class com.aibox.plugin.LoadModelResult { *; }
-keep class com.aibox.plugin.LoadModelResult$* { *; }
-keep class com.aibox.plugin.InferRequest { *; }
-keep class com.aibox.plugin.InferRequest$* { *; }
-keep class com.aibox.plugin.InferResult { *; }
-keep class com.aibox.plugin.InferResult$* { *; }

# --- 插件入口 Service:在 manifest 中以类名引用,不能被混淆 ---
-keep class com.aibox.litert.LiteRTPluginService { *; }
-keep class com.aibox.litert.LiteRTPluginService$* { *; }

# --- Binder:实现 IPluginInterface.Stub,跨进程 onTransact 分发表依赖方法 ID ---
-keep class com.aibox.litert.LiteRTBinder { *; }

# --- Descriptor:meta-data 中字符串引用,宿主 Class.forName + 反射 ---
-keep class com.aibox.litert.LiteRTDescriptor { *; }

# --- BitPositions / LiteRTLogger:公共对象,被 Kotlin 代码引用,字段名稳定 ---
-keep class com.aibox.litert.BitPositions { *; }
-keep class com.aibox.litert.LiteRTLogger { *; }

# --- Backend 类:含 native 方法,JNI 链接依赖类全限定名 ---
# nativeLoadModel / nativeUnload / nativeInfer 的 JNI 函数名
# Java_com_aibox_litert_backend_LiteRTBackend_nativeLoadModel 等
# 与 LiteRTBackend 类全限定名硬绑定,类不能被改名;native 方法签名不能被改。
-keep class com.aibox.litert.backend.LiteRTBackend { *; }
-keep class com.aibox.litert.backend.LiteRTBackend$* { *; }

# --- 多模态 pipeline / 模型类型 / 设置 ---
-keep class com.aibox.litert.backend.MultimodalPipeline { *; }
-keep class com.aibox.litert.backend.ModelType { *; }
-keep class com.aibox.litert.backend.LiteRTSettings { *; }
-keep class com.aibox.litert.backend.DelegateDetector { *; }

# --- 通用规则:所有 com.aibox.litert.* 公开类 ---
-keep public class com.aibox.litert.** { public *; }

# --- 注解保留 ---
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeInvisibleAnnotations
-keepattributes Signature
-keepattributes Deprecated
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# --- Native 方法保留 ---
# Kotlin external fun nativeXxx 编译后是 native 方法,R8 默认 keep native 方法签名,
# 这里显式再 keep 一道保险。
-keepclasseswithmembernames class * {
    native <methods>;
}
