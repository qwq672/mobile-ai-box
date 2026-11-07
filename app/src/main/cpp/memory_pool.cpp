// memory_pool.cpp
#include "memory_pool.h"
#include <android/log.h>
#include <sys/time.h>
#include <cstdlib>
#include <cstring>
#include <algorithm>

#define TAG "PocketLLM-MemPool"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)

static long now_ms() {
    struct timeval tv; gettimeofday(&tv, nullptr);
    return (long)tv.tv_sec * 1000 + tv.tv_usec / 1000;
}

void MemoryPool::warmup(int ctxLen, int physicalBatch, int batch, const std::string& kvQuant) {
    // KV: layers(32) * 2 * ctx * headDim(128) * elemSize
    double elem = (kvQuant == "q8_0") ? 1.06 :
                  (kvQuant == "q4_0") ? 0.56 : 2.0;
    long kv = (long)(32.0 * 2.0 * ctxLen * 128.0 * elem);
    long comp = 64L * 1024 * 1024 + (long)physicalBatch * 256 * 1024;
    warmup_bytes(kv, comp);
}

void MemoryPool::warmup_bytes(long kvBytes, long compBytes) {
    std::lock_guard<std::mutex> lk(m_mutex);
    m_kv_bytes = kvBytes;
    m_compute_bytes = compBytes;
    m_peak_bytes = std::max(m_peak_bytes, kvBytes + compBytes);
    m_last_use_ms = now_ms();
    LOGI("warmup kv=%ldMB comp=%ldMB", kvBytes/1024/1024, compBytes/1024/1024);
}

void MemoryPool::releaseAll() {
    std::lock_guard<std::mutex> lk(m_mutex);
    m_kv_bytes = 0;
    m_compute_bytes = 0;
    m_db_pool.clear();
    LOGI("released all");
}

void MemoryPool::shrink_idle(long idleMs, int keepTokens) {
    std::lock_guard<std::mutex> lk(m_mutex);
    long idle = now_ms() - m_last_use_ms;
    if (idle < idleMs) return;
    // 简化：直接把 KV buffer 缩到 keepTokens 对应大小
    long shrunk = (long)keepTokens * 32 * 2 * 128 * 2;  // f16 size
    if (shrunk < m_kv_bytes) {
        m_kv_bytes = shrunk;
        LOGI("shrink KV to %ldMB after idle %ldms", shrunk/1024/1024, idle);
    }
    m_last_use_ms = now_ms();
}

long MemoryPool::peak_mb() {
    return m_peak_bytes / 1024 / 1024;
}

jobject MemoryPool::borrow_direct(JNIEnv* env, int size) {
    std::lock_guard<std::mutex> lk(m_mutex);
    // 简化：每次新分配。实际可按 size 分桶复用。
    m_last_use_ms = now_ms();
    if (size <= 0) return nullptr;
    void* p = std::malloc((size_t)size);
    if (!p) {
        LOGI("borrow_direct: malloc(%d) returned null (OOM)", size);
        return nullptr;
    }
    return env->NewDirectByteBuffer(p, (jlong)size);
}

void MemoryPool::return_direct(JNIEnv* env, jobject buf) {
    std::lock_guard<std::mutex> lk(m_mutex);
    if (!buf) return;
    void* addr = env->GetDirectBufferAddress(buf);
    if (addr) std::free(addr);
    env->DeleteLocalRef(buf);
}
