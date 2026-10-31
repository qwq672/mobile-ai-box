// Top-level build file. Plugin versions are declared here (apply false),
// the :app module applies them in its own build.gradle.kts.
//
// LiteRT-LM 0.18.0 用 Kotlin 2.4 metadata 编译,本工程必须用 Kotlin 2.4.21+ 才能引用
// AGP 8.5.2 与 Kotlin 2.4.21 兼容(经测试)
// 与宿主 AIBox 工程(Kotlin 2.0.20)/Easy Chat 模块工程用不同 Kotlin 版本是 OK 的:
// - 宿主 APK 不引用 litertlm-android,无版本冲突
// - LiteRT 插件 APK 单独编译,只用 2.4.21 即可
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
}
