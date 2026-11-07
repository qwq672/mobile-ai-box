// NativeLibLoader 的 C++ 实现:基于 dlopen/dlclose + dlerror。
//
// 独立 CMake target(libaibox_module_loader.so),不依赖现有 native_bridge.cpp,
// 保证增量开发,不破坏现有可用版本。
//
// 仅依赖 <dlfcn.h>,无第三方库,可独立编译。

#include <jni.h>
#include <dlfcn.h>
#include <string>
#include <mutex>

namespace {

// dlerror 不是线程安全的,全局 mutex 保护。
// 实际上 dlerror 在 glibc 上是 thread-local,但 bionic 也保证 thread-local,
// 但保险起见对 dlerror 读取加锁(避免与 dlclose 同时调用导致字符串被覆盖)。
std::mutex g_dl_mutex;

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_aibox_infra_module_loader_NativeLibLoader_nativeDlopen(
    JNIEnv* env, jobject /*thiz*/, jstring jpath, jint jflags) {
    if (jpath == nullptr) {
        return 0;
    }
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (path == nullptr) {
        return 0;
    }

    // 清空之前的 dlerror
    dlerror();

    void* handle = dlopen(path, jflags);
    env->ReleaseStringUTFChars(jpath, path);

    if (handle == nullptr) {
        return 0;
    }
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT jboolean JNICALL
Java_com_aibox_infra_module_loader_NativeLibLoader_nativeDlclose(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong jhandle) {
    if (jhandle == 0) {
        return JNI_FALSE;
    }
    void* handle = reinterpret_cast<void*>(jhandle);

    dlerror();
    int rc = dlclose(handle);
    return (rc == 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_aibox_infra_module_loader_NativeLibLoader_nativeDlerror(
    JNIEnv* env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_dl_mutex);
    const char* err = dlerror();
    if (err == nullptr) {
        return nullptr;
    }
    return env->NewStringUTF(err);
}

}  // extern "C"
