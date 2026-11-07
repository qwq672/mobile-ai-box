# Keep native bridge — JNI 外部方法名不能被 R8 改名
-keep class com.pocketllm.infra.jni.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Keep data models used by Room / Moshi / kotlinx.serialization
-keep class com.pocketllm.data.model.** { *; }
-keep class com.pocketllm.data.db.** { *; }
-keep class com.pocketllm.domain.inference.** { *; }
-keep class com.pocketllm.domain.download.** { *; }
-keep class com.pocketllm.util.** { *; }

# Moshi Kotlin 反射：保留 data class 构造函数参数
-keepclassmembers class com.pocketllm.** { <init>(...); }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class com.pocketllm.** { kotlinx.serialization.KSerializer serializer(...); }
-keep class **$$serializer { *; }

# Room generated
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# Compose
-dontwarn androidx.compose.**

# Retrofit / OkHttp / Moshi
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-dontwarn okio.**
-dontwarn com.squareup.moshi.**
-keep class com.squareup.moshi.** { *; }
-keep class **JsonAdapter { *; }
-keepclassmembers class * { @com.squareup.moshi.* <methods>; }
# Retrofit interface: 保留带注解的接口
-keep,allowobfuscation,allowshrinking @retrofit2.http.* interface *
-keep,allowobfuscation,allowshrinking interface * { @retrofit2.http.* <methods>; }

# Coroutines / Flow
-dontwarn kotlinx.coroutines.**

# 防止 Kotlin Companion 字段被裁剪（R8 漏网）
-keepclassmembers class **$Companion { *; }

# 保留 ViewModel，因为通过 viewModel() 反射创建
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep class * extends androidx.lifecycle.AndroidViewModel { <init>(...); }
