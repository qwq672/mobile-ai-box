cmake_minimum_required(VERSION 3.22.1)
project(aibox_litert LANGUAGES C CXX)

# ============================================================
# aibox_litert 桥接库
# ============================================================
# 本 CMakeLists 编译 libaibox_litert.so(Kotlin 侧 LiteRTBackend 的 JNI 桥接层)。
#
# 当前阶段(骨架):只编译 litert_bridge.cpp,产出"空" .so 让 System.loadLibrary 可加载。
#
# 后续 CI 注入 LiteRT-LM SDK 时:
#   1. 把 LiteRT-LM Android AAR 解压,提取 jni/arm64-v8a/liblitert_lm.so 等 .so 到
#      `app/src/main/jniLibs/arm64-v8a/`(已在仓库中建好,带 .gitkeep)。
#   2. 把 LiteRT-LM 头文件放到 `app/src/main/cpp/include/`(后续 CI 创建)。
#   3. 在此 CMakeLists 加:
#        include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include)
#        add_library(litert_lm SHARED IMPORTED)
#        set_target_properties(litert_lm PROPERTIES
#            IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/liblitert_lm.so)
#        target_link_libraries(aibox_litert litert_lm)
#   4. 把 litert_bridge.cpp 内的 TODO 实现真实 LiteRT-LM API 调用。
#
# Hexagon NPU(QNN SDK)也按相同模式注入:
#   - libQnnHtp.so / libQnnSystem.so 等放到 jniLibs/arm64-v8a/。
#   - 头文件 QnnInterface.h 等放到 cpp/include/QNN/。
#   - target_link_libraries(aibox_litert QnnHtp QnnSystem ...)。

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 启用异常与 RTTI(LiteRT-LM SDK 内部用 RTTI 做动态类型分派)。
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fexceptions -frtti")

add_library(aibox_litert SHARED
    litert_bridge.cpp
)

# ============================================================
# 系统库
# ============================================================
find_library(LOG_LIB log REQUIRED)
target_link_libraries(aibox_litert ${LOG_LIB})

# 后续 SDK 注入时,在此处追加:
# - target_include_directories(aibox_litert PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
# - target_link_libraries(aibox_litert litert_lm)
# - target_link_libraries(aibox_litert QnnHtp QnnSystem)  # Hexagon NPU

# ============================================================
# ABI 控制:仅 arm64-v8a
# ============================================================
# 由 app/build.gradle.kts 的 defaultConfig.ndk.abiFilters + externalNativeBuild.cmake.cppFlags
# 限制;此处不重复设置,避免与 Gradle 冲突。
