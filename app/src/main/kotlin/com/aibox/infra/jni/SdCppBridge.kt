package com.aibox.infra.jni

/**
 * sd.cpp 生图推理框架的 JNI 门面（阶段一：框架骨架）。
 *
 * 对应 native 侧 app/src/main/cpp/sdcpp_bridge.cpp（独立库 libaibox_sdcpp.so）。
 * 当前所有方法都有明确的 stub 返回值（available=false / 加载失败），
 * UI 应据此展示"框架就绪，推理未接入"，而不是当作真的能出图。
 *
 * 后续接入 sd.cpp 真实引擎时，只需替换 native 实现，本门面接口保持不变。
 * 每个 external fun 都标注 @JvmStatic，保证 JNI 符号名为
 * Java_com_aibox_infra_jni_SdCppBridge_xxx（不带 object 实例后缀）。
 */
object SdCppBridge {

    init {
        System.loadLibrary("aibox_sdcpp")
    }

    /** 框架版本标识，供诊断展示。 */
    @JvmStatic external fun sdVersion(): String

    /** sd.cpp 引擎是否已接入且可用。阶段一恒为 false。 */
    @JvmStatic external fun sdAvailable(): Boolean

    /**
     * 加载生图模型。
     * @param modelDir 模型文件目录（含 diffusion / vae / text_encoder / tokenizer）
     * @param backend  后端 id（预留：0=CPU, 1=NPU/QNN 等）
     * @return 阶段一恒为 false
     */
    @JvmStatic external fun sdLoadModel(modelDir: String, backend: Int): Boolean

    /**
     * 文生图。阶段一返回 false 且不产出图片。
     * @return 是否成功出图（阶段一恒为 false）
     */
    @JvmStatic external fun sdTxt2Img(
        prompt: String,
        negative: String,
        outPath: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long
    ): Boolean

    /** 卸载模型、释放资源。 */
    @JvmStatic external fun sdUnload()
}
