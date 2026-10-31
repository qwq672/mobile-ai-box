package com.aibox.feature.image

/**
 * 图像能力完整契约 — 4 种能力。
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 能力分类（用户要求）：
 *   1. [ImageGenerator]   : 文生图 / 图生图（原有）
 *   2. [ImageEditor]       : 提示词编辑 / 局部重绘
 *   3. [ImageUpscaler]     : 超分辨率放大（Real-ESRGAN）
 *   4. [ImageEraser]       : 圈选擦除 / 涂抹擦除（LaMa / Inpaint-Anything）
 *
 * 反屎山原则：
 * - 接口零依赖：4 种能力各自独立接口，插件可只实现其中一种
 * - 统一输入输出：所有路径用 [ImagePath]，避免 Bitmap 跨进程传输
 * - 模型分离：每种能力对应一个模型，按需下载
 * - 流式进度：所有能力都通过 [onProgress] 回调让 UI 显示进度
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 */
public interface ImageCapability {
    public val kind: ImageCapabilityKind
}

public enum class ImageCapabilityKind {
    GENERATION,  // 文生图
    EDITING,     // 编辑
    UPSCALING,   // 超分
    ERASING,     // 擦除
}

/** 图像路径占位（实际为 String URI 或文件路径） */
public typealias ImagePath = String

/** 进度回调（0..1） */
public fun interface ImageProgressCallback {
    public fun onProgress(ratio: Float, message: String)
}

// ══════════════════════════════════════════════════════════
// 1. 图像生成（文生图 / 图生图）
// ══════════════════════════════════════════════════════════
public interface ImageGenerator : ImageCapability {
    override val kind: ImageCapabilityKind get() = ImageCapabilityKind.GENERATION

    public suspend fun textToImage(
        request: TextToImageRequest,
        onProgress: ImageProgressCallback,
    ): com.aibox.core.common.Outcome<ImageGenResult>

    public suspend fun imageToImage(
        request: ImageToImageRequest,
        onProgress: ImageProgressCallback,
    ): com.aibox.core.common.Outcome<ImageGenResult>
}

public data class TextToImageRequest(
    public val prompt: String,
    public val negativePrompt: String = "",
    public val width: Int = 512,
    public val height: Int = 512,
    public val steps: Int = 20,
    public val seed: Long = -1L,
    public val backendId: String? = null,
)

public data class ImageToImageRequest(
    public val sourceImage: ImagePath,
    public val prompt: String,
    public val denoisingStrength: Float = 0.6f,  // 0..1，1=完全重画
    public val negativePrompt: String = "",
    public val steps: Int = 20,
    public val seed: Long = -1L,
    public val backendId: String? = null,
)

public data class ImageGenResult(
    public val imagePath: ImagePath,
    public val seedUsed: Long,
    public val generationMs: Long,
    public val npuUsed: Boolean,
)

// ══════════════════════════════════════════════════════════
// 2. 图像编辑（提示词编辑 / 局部重绘 inpainting）
// ══════════════════════════════════════════════════════════
public interface ImageEditor : ImageCapability {
    override val kind: ImageCapabilityKind get() = ImageCapabilityKind.EDITING

    /**
     * 提示词编辑（自然语言修改图片）。
     *
     * 例：
     *   sourceImage = "photo.jpg"
     *   instruction = "把背景换成海边"
     *   返回：编辑后的图
     */
    public suspend fun instructEdit(
        request: InstructEditRequest,
        onProgress: ImageProgressCallback,
    ): com.aibox.core.common.Outcome<ImageGenResult>

    /**
     * 局部重绘（inpainting）。
     *
     * 用户指定一个 mask（黑白图，白色为要重绘的区域），AI 只修改 mask 区域。
     */
    public suspend fun inpaint(
        request: InpaintRequest,
        onProgress: ImageProgressCallback,
    ): com.aibox.core.common.Outcome<ImageGenResult>
}

public data class InstructEditRequest(
    public val sourceImage: ImagePath,
    public val instruction: String,
    public val strength: Float = 0.7f,
    public val backendId: String? = null,
)

public data class InpaintRequest(
    public val sourceImage: ImagePath,
    /** mask 路径（黑白图，白色区域将被重绘） */
    public val maskPath: ImagePath,
    public val prompt: String,
    public val negativePrompt: String = "",
    public val denoisingStrength: Float = 1.0f,
    public val steps: Int = 20,
    public val backendId: String? = null,
)

// ══════════════════════════════════════════════════════════
// 3. 超分辨率放大（Real-ESRGAN 等）
// ══════════════════════════════════════════════════════════
public interface ImageUpscaler : ImageCapability {
    override val kind: ImageCapabilityKind get() = ImageCapabilityKind.UPSCALING

    public suspend fun upscale(
        request: UpscaleRequest,
        onProgress: ImageProgressCallback,
    ): com.aibox.core.common.Outcome<UpscaleResult>
}

public data class UpscaleRequest(
    public val sourceImage: ImagePath,
    public val scale: UpscaleScale = UpscaleScale.X2,
    public val model: UpscaleModel = UpscaleModel.REAL_ESRGAN_X4PLUS,
    public val backendId: String? = null,
    /** 是否启用 face enhancement（GFPGAN 联动） */
    public val enhanceFaces: Boolean = false,
)

public enum class UpscaleScale(public val factor: Int) {
    X2(2), X4(4), X8(8)
}

public enum class UpscaleModel(public val displayName: String) {
    REAL_ESRGAN_X4PLUS("Real-ESRGAN x4plus"),
    REAL_ESRGAN_X4PLUS_ANIME_6B("Real-ESRGAN anime6B"),
    SWINIR("SwinIR"),
    HAT("HAT"),
}

public data class UpscaleResult(
    public val imagePath: ImagePath,
    public val originalWidth: Int,
    public val originalHeight: Int,
    public val outputWidth: Int,
    public val outputHeight: Int,
    public val durationMs: Long,
    public val npuUsed: Boolean,
)

// ══════════════════════════════════════════════════════════
// 4. 图像擦除（圈选/涂抹去除物体）
// ══════════════════════════════════════════════════════════
public interface ImageEraser : ImageCapability {
    override val kind: ImageCapabilityKind get() = ImageCapabilityKind.ERASING

    /**
     * 擦除用户圈选的区域。
     *
     * 用户在 UI 上用画笔画出要擦除的区域 → 生成 mask → 调本接口。
     * 后端用 LaMa / Inpaint-Anything 等模型补全背景。
     *
     * @param request 包含原图、mask（白色是要擦除的区域）
     */
    public suspend fun erase(
        request: EraseRequest,
        onProgress: ImageProgressCallback,
    ): com.aibox.core.common.Outcome<EraseResult>

    /**
     * 物体擦除（基于自然语言识别，无需 mask）。
     *
     * 例：sourceImage + "擦除图片中所有的路人"
     * 后端先用 grounding DINO 等模型检测物体位置，再 inpaint。
     */
    public suspend fun eraseByInstruction(
        request: EraseByInstructionRequest,
        onProgress: ImageProgressCallback,
    ): com.aibox.core.common.Outcome<EraseResult>
}

public data class EraseRequest(
    public val sourceImage: ImagePath,
    public val maskPath: ImagePath,           // 白色 = 要擦除
    public val model: EraseModel = EraseModel.LAMA,
    public val backendId: String? = null,
)

public data class EraseByInstructionRequest(
    public val sourceImage: ImagePath,
    public val instruction: String,           // "擦除图片中所有的路人"
    public val model: EraseModel = EraseModel.LAMA,
    public val backendId: String? = null,
)

public enum class EraseModel(public val displayName: String) {
    LAMA("LaMa (快速)"),
    INPAINT_ANYTHING("Inpaint-Anything (高质量)"),
    STABLE_INPAINT("SD Inpaint (多功能)"),
}

public data class EraseResult(
    public val imagePath: ImagePath,
    public val durationMs: Long,
    public val modelUsed: EraseModel,
    public val npuUsed: Boolean,
)
