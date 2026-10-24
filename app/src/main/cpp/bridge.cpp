// bridge.cpp
#include "bridge.h"
#include "llama.h"
#include <mutex>
#include <fstream>
#include <string>

// 用 mutex 替代 std::atomic<bridge_sampler_t>，避免 16 字节原子操作在某些平台
// （如 32 位 ARM、部分 NDK 工具链）需要链接 __atomic_store_16 的可移植性问题。
static std::mutex g_sampler_mtx;
static bridge_sampler_t g_sampler{ 0.8f, 40, 0.95f, 1.1f };

// vocab 指针由 native_bridge 在 load 模型后注入；penalties 采样器需要真实词表大小
static std::mutex g_vocab_mtx;
static const llama_vocab* g_vocab = nullptr;

void bridge_set_sampler(const bridge_sampler_t& s) {
    std::lock_guard<std::mutex> lk(g_sampler_mtx);
    g_sampler = s;
}

void bridge_set_vocab(const llama_vocab* vocab) {
    std::lock_guard<std::mutex> lk(g_vocab_mtx);
    g_vocab = vocab;
}

llama_sampler* bridge_build_sampler() {
    bridge_sampler_t s;
    {
        std::lock_guard<std::mutex> lk(g_sampler_mtx);
        s = g_sampler;
    }
    int32_t n_vocab = 0;
    {
        std::lock_guard<std::mutex> lk(g_vocab_mtx);
        if (g_vocab) n_vocab = llama_vocab_n_tokens(g_vocab);
    }
    if (n_vocab <= 0) n_vocab = 32000; // 兜底，理论上不该走到

    auto chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    // llama.cpp 0.4.x: (n_vocab, penalty_last_n, penalty_repeat, penalty_freq, penalty_present)
    llama_sampler_chain_add(chain, llama_sampler_init_penalties(n_vocab, 64, s.repeat_penalty, 0.0f, 0.0f));
    llama_sampler_chain_add(chain, llama_sampler_init_top_k(s.top_k));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(s.top_p, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp (s.temperature));
    llama_sampler_chain_add(chain, llama_sampler_init_dist (0));
    return chain;
}

// 从 sysfs 读 CPU 温度，转 0-100
int bridge_read_thermal_percent() {
    static const char* zones[] = {
        "/sys/class/thermal/thermal_zone0/temp",
        "/sys/class/thermal/thermal_zone1/temp",
    };
    for (const char* z : zones) {
        std::ifstream f(z);
        if (f) {
            long milli = 0; f >> milli;
            double c = milli / 1000.0;
            double pct = (c - 35.0) * 100.0 / 25.0;
            if (pct < 0)   pct = 0;
            if (pct > 100) pct = 100;
            return (int)pct;
        }
    }
    return 0;
}
