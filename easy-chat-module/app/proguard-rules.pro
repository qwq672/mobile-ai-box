# ============================================================
# Easy Chat 模块 ProGuard 规则
# ============================================================
# 本 APK 不打包推理框架(那是宿主的职责),代码量极小,但还是要保住
# AIDL 生成的接口 + Stub + data class 不被混淆或剥离。

# --- AIDL 生成的接口与 Stub ---
# IChatModuleInterface 由 aidl 工具生成,包名 com.aibox.plugin。
# 宿主侧也用同名接口绑定,字段 / 方法签名必须二进制一致。
-keep interface com.aibox.plugin.IChatModuleInterface { *; }
-keep class com.aibox.plugin.IChatModuleInterface$Stub { *; }
-keep class com.aibox.plugin.IChatModuleInterface$Stub$Proxy { *; }
# 旧版 aidl 生成的内部 Default 包装
-keep class com.aibox.plugin.IChatModuleInterface$Default { *; }

# --- 模块入口 Service ---
# EasyChatModuleService 在 AndroidManifest 中声明 exported=true,
# 宿主通过 Intent Action 绑定,类名不能被混淆(否则宿主查不到)。
-keep class com.easychat.EasyChatModuleService { *; }
-keep class com.easychat.EasyChatModuleService$* { *; }

# --- 模块 Binder ---
# EasyChatModuleBinder 继承 IChatModuleInterface.Stub,跨进程的
# onTransact 分发表依赖方法名 → int 的映射,不能被混淆。
-keep class com.easychat.EasyChatModuleBinder { *; }

# --- 模块描述符 ---
# 在 Service meta-data 中以字符串引用("com.easychat.EasyChatModuleDescriptor"),
# 宿主后续可能用 Class.forName 加载它,类名 + 字段名都不能被混淆。
-keep class com.easychat.EasyChatModuleDescriptor { *; }

# --- 通用规则:所有 com.easychat.* 公开类 ---
# 防止 R8 把模块对外暴露的类削成最小可见性。
-keep public class com.easychat.** { public *; }

# --- AndroidX / Kotlin stdlib 不需要手动 keep,默认 consumer-rules 已处理 ---

# --- 注解保留 ---
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeInvisibleAnnotations
-keepattributes Signature
-keepattributes Deprecated
-keepattributes EnclosingMethod
-keepattributes InnerClasses
