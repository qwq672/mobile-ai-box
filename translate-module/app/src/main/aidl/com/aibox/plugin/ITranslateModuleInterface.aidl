// ITranslateModuleInterface.aidl
package com.aibox.plugin;

import com.aibox.plugin.ITranslateCallback;

/**
 * 翻译模块对外接口(模块侧实现,宿主侧调用)。
 *
 * 详见 ITranslateCallback.aidl 获取回调接口定义。
 */
interface ITranslateModuleInterface {
    /** 返回 assets/module.json 的原始内容;失败返回空串。 */
    String getModuleMetadata();

    /** 返回支持的语言列表(JSON 数组字符串);失败返回空串。 */
    String getSupportedLanguages();

    /**
     * 启动一次翻译会话;结果通过 ITranslateCallback 异步回传。
     *
     * @param sourceText 待翻译文本;若为空串,模块将走 ASR 路径。
     * @param sourceLang 源语言 BCP-47 标签(如 "zh-CN" / "auto")。
     * @param targetLang 目标语言 BCP-47 标签(如 "en-US")。
     * @param sessionId  宿主生成的唯一会话标识;空串时模块内部生成。
     * @return 启动成功返回 sessionId;失败返回空串。
     */
    String startTranslation(String sourceText, String sourceLang, String targetLang, String sessionId);

    /** 取消正在进行的翻译会话;幂等。 */
    void cancelTranslation(String sessionId);

    /** 注入翻译回调;传 null 解除。 */
    void setTranslationCallback(ITranslateCallback callback);
}
