// litert_bridge.cpp
//
// JNI 桥接骨架:把 Kotlin 侧 LiteRTBackend.nativeLoadModel / nativeUnload / nativeInfer
// 调用转发到 LiteRT-LM SDK(后续 CI 注入)。
//
// 当前阶段(api 骨架):所有函数返回占位值(0 / 空 / "{}"),不链接任何真实 SDK;
// 仅保证 libaibox_litert.so 可被 System.loadLibrary 加载,且 JNI 签名与 LiteRTBackend.kt
// 中的 external fun 声明严格匹配。
//
// 后续 CI 注入 LiteRT-LM .so 步骤:
//   1. 把 LiteRT-LM Android AAR 解压,提取 jni/arm64-v8a/liblitert_lm.so 等 .so 到
//      `app/src/main/jniLibs/arm64-v8a/`(已建好目录,带 .gitkeep)。
//   2. 把 LiteRT-LM 头文件 `include/litert/litert.h` 等放到 `app/src/main/cpp/include/`。
//   3. 在 CMakeLists.txt 加 `add_subdirectory(...)` 或 `find_library(LITERT_LM litert_lm)`,
//      并 `target_link_libraries(aibox_litert ${LITERT_LM})`。
//   4. 在下方 nativeLoadModel 真实调用 LiteRT-LM API(如 LiteRtModelInit / LiteRtGetSignature)。
//   5. 重新打包;APK 内 lib/arm64-v8a/ 会同时含 libaibox_litert.so + liblitert_lm.so。
//
// 命名约定:JNI 函数名 = Java_<全限定类名用下划线替代点>_<方法名>。
//   类:com.aibox.litert.backend.LiteRTBackend
//   方法:nativeLoadModel / nativeUnload / nativeInfer
//   → Java_com_aibox_litert_backend_LiteRTBackend_nativeLoadModel 等。

#include <jni.h>
#include <string>
#include <android/log.h>

#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO,  "AIBox-LiteRT", __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, "AIBox-LiteRT", __VA_ARGS__)

// 后续 LiteRT-LM SDK 接入后,在此 include 头文件:
// #include "litert/litert.h"
// #include "tflite/delegates/nnapi/nnapi_delegate.h"
// #include "tflite/delegates/gpu/delegate.h"

namespace {

// 占位:返回固定的"无错误"结果 JSON。
// 真实 SDK 接入后,这里会返回 ModalityOutput.Text 序列化结果(由 LiteRT-LM generate 输出)。
const char* kPlaceholderResultJson = "{}";

// 占位:简单的 logcat 输出,标记 native 已被调用。
// 真实 SDK 接入后,这里应桥接到 Kotlin 侧 LiteRTLogger(通过 JNI 调 LiteRTLogger.log 静态方法)。
void logNative(const char* tag, const char* msg) {
    __android_log_print(ANDROID_LOG_DEBUG, tag, "%s", msg);
}

}  // namespace

extern "C" {

// ============================================================
// JNI 接口实现:与 LiteRTBackend.kt 中的 external fun 严格对齐。
// ============================================================

/**
 * 加载模型,返回 native 句柄;0 表示失败或骨架占位。
 *
 * @param path jstring,模型路径(UTF-8)。
 * @param delegate jstring,"npu" / "gpu" / "cpu"。
 * @param enableVision jboolean,是否启用 Siglip vision encoder。
 * @return jlong,native 模型句柄(骨架版固定返回 0)。
 */
JNIEXPORT jlong JNICALL
Java_com_aibox_litert_backend_LiteRTBackend_nativeLoadModel(
    JNIEnv* env, jobject /*thiz*/, jstring path, jstring delegate, jboolean enableVision) {
    const char* path_c = env->GetStringUTFChars(path, nullptr);
    const char* delegate_c = env->GetStringUTFChars(delegate, nullptr);
    logNative("aibox.plugin.litert.native",
              "[skeleton] nativeLoadModel called (placeholder)");
    logNative("aibox.plugin.litert.native", path_c);
    logNative("aibox.plugin.litert.native", delegate_c);
    (void)enableVision;  // 骨架阶段忽略
    // TODO: 后续集成 LiteRT-LM SDK:
    //   1. 调 LiteRtModelInit(path_c) 加载 .tflite。
    //   2. 根据 delegate_c 选择 NNAPI Delegate (Hexagon NPU) / GPU Delegate / CPU。
    //   3. enableVision=true 时额外加载 Siglip vision encoder .tflite。
    //   4. 返回 LiteRT-LM 模型句柄(强转 jlong)。
    env->ReleaseStringUTFChars(path, path_c);
    env->ReleaseStringUTFChars(delegate, delegate_c);
    return 0;  // 骨架占位
}

/**
 * 释放 native 句柄对应资源。
 *
 * @param handle jlong,nativeLoadModel 返回的句柄。
 */
JNIEXPORT void JNICALL
Java_com_aibox_litert_backend_LiteRTBackend_nativeUnload(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    logNative("aibox.plugin.litert.native",
              "[skeleton] nativeUnload called (placeholder)");
    (void)handle;
    // TODO: 后续 LiteRtModelDestroy(handle) + 释放 delegate 资源。
}

/**
 * 同步推理;返回 ModalityOutput JSON 字符串。
 *
 * @param handle jlong,nativeLoadModel 返回的句柄。
 * @param requestJson jstring,JSON 序列化推理请求(含 text / imageEmbeddings / delegate / modelType)。
 * @return jstring,ModalityOutput.Text JSON;骨架版返回 "{}"。
 */
JNIEXPORT jstring JNICALL
Java_com_aibox_litert_backend_LiteRTBackend_nativeInfer(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jstring requestJson) {
    (void)handle;
    if (requestJson != nullptr) {
        const char* req_c = env->GetStringUTFChars(requestJson, nullptr);
        logNative("aibox.plugin.litert.native", req_c);
        env->ReleaseStringUTFChars(requestJson, req_c);
    }
    // TODO: 后续接入 LiteRT-LM:
    //   1. 解析 requestJson(text / imageEmbeddings)。
    //   2. Siglip 编码图像(若含 imageEmbeddings)。
    //   3. Gemma 自回归生成(逐 token decode + KV cache 更新)。
    //   4. 序列化 ModalityOutput.Text 为 JSON 字符串返回。
    return env->NewStringUTF(kPlaceholderResultJson);
}

}  // extern "C"
