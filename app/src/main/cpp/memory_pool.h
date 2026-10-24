// memory_pool.h
//
// 池化内存管理：避免每 token 反复 malloc/free（手机端发烫的真凶）。
//
// 三个池：
//   1. KV cache pool —— 在 load 时按 max_ctx 预分配
//   2. Compute buffer pool —— 按 physical_batch 预分配
//   3. DirectByteBuffer pool —— JNI 传 token id 用，避免每 token 一次 alloc
#pragma once
#include <jni.h>
#include <cstddef>
#include <vector>
#include <mutex>
#include <unordered_map>

class MemoryPool {
public:
    // 按 llama.cpp 配置估算并预热
    void warmup(int ctxLen, int physicalBatch, int batch, const std::string& kvQuant);
    void warmup_bytes(long kvBytes, long compBytes);
    void releaseAll();
    void shrink_idle(long idleMs, int keepTokens);
    long peak_mb();

    // DirectByteBuffer 池
    jobject borrow_direct(JNIEnv* env, int size);
    void   return_direct(JNIEnv* env, jobject buf);

private:
    std::mutex m_mutex;
    long m_kv_bytes     = 0;
    long m_compute_bytes = 0;
    long m_peak_bytes    = 0;
    long m_last_use_ms   = 0;
    std::vector<void*>   m_db_pool;       // direct buffer 池
};
