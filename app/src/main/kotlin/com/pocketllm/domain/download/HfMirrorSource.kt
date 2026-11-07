package com.pocketllm.domain.download

/**
 * HF-Mirror：HuggingFace 的国内镜像，URL 路径完全兼容，只换域名。
 * 直接继承 HuggingFaceSource 即可。
 */
class HfMirrorSource : HuggingFaceSource() {
    override val id = "hf_mirror"
    override val displayNameZh = "HF-Mirror（国内镜像）"
    override val displayNameEn = "HF-Mirror (China)"
    override val baseUrl = "https://hf-mirror.com"
}
