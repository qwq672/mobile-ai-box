package com.aibox.domain.download

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

/**
 * HF-Mirror 的备用域名。
 *
 * 部分网络环境下 hf-mirror.com 不可达，但 hf-mirror.net 可用；
 * 由下载页的「下载失败时改用 hf-mirror.net」开关控制是否回退。
 */
class HfMirrorNetSource : HuggingFaceSource() {
    override val id = "hf_mirror_net"
    override val displayNameZh = "HF-Mirror（备用 .net）"
    override val displayNameEn = "HF-Mirror (.net)"
    override val baseUrl = "https://hf-mirror.net"
}
