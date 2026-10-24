// ITranslateCallback.aidl
package com.aibox.plugin;

/**
 * 翻译回调接口(宿主侧实现,模块侧调用)。
 *
 * 一次翻译会话的回调顺序:
 * 1. onProgress(step=1, stepName="ASR")     — 开始 ASR(若需)
 * 2. onProgress(step=2, stepName="MT")      — 开始 MT
 *    onPartialResult(partialTranslation)    — 可多次
 * 3. onProgress(step=3, stepName="TTS")     — 开始 TTS
 * 4. onComplete(finalTranslation, ttsAudioPath) — 全部完成
 *
 * 或失败路径: onProgress(...) → onError(reason)
 * 取消路径: cancelTranslation(sessionId) → onError("cancelled")
 * 一次会话只会有一个终结回调(不会重复 onComplete / onError)。
 */
interface ITranslateCallback {
    /**
     * 翻译进度。
     * @param step 步骤序号(1=ASR, 2=MT, 3=TTS)。
     * @param stepName 步骤名("ASR" / "MT" / "TTS")。
     */
    void onProgress(int step, String stepName);

    /** 增量翻译结果(仅 MT 步骤可能调用)。 */
    void onPartialResult(String partialTranslation);

    /**
     * 翻译完成。
     * @param finalTranslation 最终译文。
     * @param ttsAudioPath TTS 音频文件路径;空串表示未落盘。
     */
    void onComplete(String finalTranslation, String ttsAudioPath);

    /**
     * 翻译失败;调用后模块不会再调本 callback 的任何方法。
     * @param reason 失败原因(如 "ASR unavailable" / "cancelled")。
     */
    void onError(String reason);
}
