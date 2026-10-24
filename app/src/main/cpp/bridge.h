// bridge.h —— 抽象出与 llama.cpp 版本相关的细节
#pragma once
#include <string>

struct bridge_sampler_t {
    float temperature;
    int   top_k;
    float top_p;
    float repeat_penalty;
};

struct llama_sampler;
struct llama_vocab;

void                  bridge_set_sampler(const bridge_sampler_t& s);
// 由 native_bridge 在 load 模型后注入 vocab，采样器需要它来分配 logits buffer
void                  bridge_set_vocab(const struct llama_vocab* vocab);
struct llama_sampler* bridge_build_sampler();
int                   bridge_read_thermal_percent();
