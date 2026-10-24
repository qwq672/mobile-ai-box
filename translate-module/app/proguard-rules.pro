# ============================================================
# 翻译模块 ProGuard 规则
# ============================================================
# 本 APK 不打包推理框架(那是宿主的职责),代码量小,但还是要保住
# AIDL 生成的接口 + Stub + data class 不被混淆或剥离。

# --- AIDL 生成的接口与 Stub ---
# ITranslateModuleInterface / ITranslateCallback 由 aidl 工具生成,
# 包名 com.aibox.plugin。宿主侧也用同名接口绑定,字段 / 方法签名必须二进制一致。
-keep interface com.aibox.plugin.ITranslateModuleInterface { *; }
-keep class com.aibox.plugin.ITranslateModuleInterface$Stub { *; }
-keep class com.aibox.plugin.ITranslateModuleInterface$Stub$Proxy { *; }
-keep class com.aibox.plugin.ITranslateModuleInterface$Default { *; }

-keep interface com.aibox.plugin.ITranslateCallback { *; }
-keep class com.aibox.plugin.ITranslateCallback$Stub { *; }
-keep class com.aibox.plugin.ITranslateCallback$Stub$Proxy { *; }
-keep class com.aibox.plugin.ITranslateCallback$Default { *; }

# --- 模块入口 Service ---
# TranslateModuleService 在 AndroidManifest 中声明 exported=true,
# 宿主通过 Intent Action 绑定,类名不能被混淆(否则宿主查不到)。
-keep class com.aibox.translate.TranslateModuleService { *; }
-keep class com.aibox.translate.TranslateModuleService$* { *; }

# --- 模块 Binder ---
# TranslateModuleBinder 继承 ITranslateModuleInterface.Stub,跨进程的
# onTransact 分发表依赖方法名 → int 的映射,不能被混淆。
-keep class com.aibox.translate.TranslateModuleBinder { *; }

# --- 模块描述符 ---
# 在 Service meta-data 中以字符串引用("com.aibox.translate.TranslateModuleDescriptor"),
# 宿主后续可能用 Class.forName 加载它,类名 + 字段名都不能被混淆。
-keep class com.aibox.translate.TranslateModuleDescriptor { *; }

# --- 翻译协调器 + 宿主能力桥接口 ---
# TranslationOrchestrator / TranslationHostBridge 是模块核心逻辑类,
# 内部用反射 / data class 字段名,保留以避免运行期 ClassCastException。
-keep class com.aibox.translate.TranslationOrchestrator { *; }
-keep interface com.aibox.translate.TranslationHostBridge { *; }
-keep class com.aibox.translate.TranslationResult { *; }
-keep interface com.aibox.translate.TranslationCallback { *; }
-keep class com.aibox.translate.Outcome { *; }
-keep class com.aibox.translate.Outcome$* { *; }

# --- 通用规则:所有 com.aibox.translate.* 公开类 ---
# 防止 R8 把模块对外暴露的类削成最小可见性。
-keep public class com.aibox.translate.** { public *; }

# --- kotlinx-coroutines 默认 consumer-rules 已处理,无需手动 keep ---

# --- 注解保留 ---
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeInvisibleAnnotations
-keepattributes Signature
-keepattributes Deprecated
-keepattributes EnclosingMethod
-keepattributes InnerClasses
