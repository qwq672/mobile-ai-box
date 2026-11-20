// sd.cpp 生图推理框架的 JNI 骨架（阶段一：可编译、可加载、明确 stub）。
//
// 设计目标（与 litert_bridge / module_loader_jni 一致的增量开发思路）：
// - 独立 CMake target(libaibox_sdcpp.so)，不依赖 llama.cpp / ggml，
//   保证不破坏现有可用版本；
// - 这里只暴露稳定的 JNI 接口与占位实现，真正接入 sd.cpp 源码时
//   只需替换 sdLoadModel / sdTxt2Img 的内部实现，Kotlin 侧无需改动；
// - 当前所有推理函数返回明确标记（available=false / 空图），UI 能据此
//   显示"框架就绪，推理未接入"，而不是崩溃或假装成功。
//
// 后续接入 sd.cpp 的真实步骤（阶段二）：
//   1. 把 sd.cpp 源码放到 app/src/main/cpp/sd.cpp，用 add_subdirectory 引入；
//   2. 在 sdLoadModel 里创建 stable_diffusion 上下文，加载 diffusion/vae/text_encoder；
//   3. 在 sdTxt2Img 里跑图并把像素写到输出文件路径；
//   4. 异步化（参考 llama 的 completion 回调），避免阻塞 UI 线程。

#include <jni.h>
#include <android/log.h>
#include <string>

#define SDCPP_LOG_TAG "aibox.sdcpp"
#define SDLOGI(...) __android_log_print(ANDROID_LOG_INFO, SDCPP_LOG_TAG, __VA_ARGS__)

extern "C" {

/** 框架版本 / 标识，供 UI 诊断展示。 */
JNIEXPORT jstring JNICALL
Java_com_aibox_infra_jni_SdCppBridge_sdVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF("sd.cpp@0.1.0-stub");
}

/**
 * sd.cpp 后端在当前设备上是否可用。
 * 阶段一恒为 false（未编译进 sd.cpp 引擎）；阶段二改为检查 .so / 模型能力后返回 true。
 */
JNIEXPORT jboolean JNICALL
Java_com_aibox_infra_jni_SdCppBridge_sdAvailable(JNIEnv*, jclass) {
    SDLOGI("sdAvailable: stub -> false (sd.cpp engine not wired yet)");
    return JNI_FALSE;
}

/**
 * 加载生图模型（diffusion / vae / text_encoder / tokenizer）。
 * 阶段一：仅记录参数并返回 false。
 * @param jmodelDir 模型文件所在目录
 */
JNIEXPORT jboolean JNICALL
Java_com_aibox_infra_jni_SdCppBridge_sdLoadModel(
        JNIEnv* env, jclass, jstring jmodelDir, jint jbackend) {
    (void)jbackend;
    if (!jmodelDir) return JNI_FALSE;
    const char* dir = env->GetStringUTFChars(jmodelDir, nullptr);
    SDLOGI("sdLoadModel(stub): dir=%s backend=%d", dir ? dir : "(null)", (int)jbackend);
    if (dir) env->ReleaseStringUTFChars(jmodelDir, dir);
    return JNI_FALSE;
}

/**
 * 文生图。阶段一：写出空文件占位并返回 false，UI 据此提示"推理未接入"。
 * @param jprompt 正向提示词
 * @param jnegative 负向提示词
 * @param joutPath 输出图片路径
 */
JNIEXPORT jboolean JNICALL
Java_com_aibox_infra_jni_SdCppBridge_sdTxt2Img(
        JNIEnv* env, jclass, jstring jprompt, jstring jnegative, jstring joutPath,
        jint jwidth, jint jheight, jint jsteps, jfloat jcfg, jlong jseed) {
    if (!joutPath) return JNI_FALSE;
    const char* out = env->GetStringUTFChars(joutPath, nullptr);
    SDLOGI("sdTxt2Img(stub): %dx%d steps=%d cfg=%.2f seed=%lld out=%s",
           (int)jwidth, (int)jheight, (int)jsteps, (double)jcfg, (long long)jseed,
           out ? out : "(null)");
    if (out) env->ReleaseStringUTFChars(joutPath, out);
    (void)jprompt; (void)jnegative;
    return JNI_FALSE;
}

/** 卸载模型、释放资源。阶段一为空实现。 */
JNIEXPORT void JNICALL
Java_com_aibox_infra_jni_SdCppBridge_sdUnload(JNIEnv*, jclass) {
    SDLOGI("sdUnload(stub)");
}

}  // extern "C"
