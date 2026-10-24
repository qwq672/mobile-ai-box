package com.aibox.domain.search

import com.aibox.data.repo.ChatRepository
import com.aibox.data.repo.ModelRepository
import com.aibox.di.AppContainer
import com.aibox.domain.search.providers.BenchmarkSearchProvider
import com.aibox.domain.search.providers.ChatMessageSearchProvider
import com.aibox.domain.search.providers.ChatSearchProvider
import com.aibox.domain.search.providers.ModelSearchProvider
import com.aibox.domain.search.providers.ModuleSearchProvider
import com.aibox.domain.search.providers.SettingSearchProvider
import com.aibox.domain.search.providers.ToolSearchProvider
import com.aibox.util.log.LogSystem

/**
 * 搜索引擎工厂：把宿主侧所有 Provider 一次性组装成 [SearchEngine]。
 *
 * 注：[ToolSearchProvider] 当前为占位（待 B1 ToolRegistry 实现后接入）。
 *
 * 工厂职责：
 * 1. 在 AppContainer 构造时调用一次，缓存到 [AppContainer.searchEngine]。
 * 2. 后续 [com.aibox.ui.search.SearchViewModel] 通过 `AIBoxApp.instance.container.searchEngine`
 *    获取；不在 UI 层重复构造。
 */
object SearchEngineFactory {

    private const val TAG = "aibox.search.factory"

    /**
     * 创建 [SearchEngine]。
     *
     * @param container 宿主 DI 容器；本工厂从容器取 [ChatRepository] / [ModelRepository] /
     *   [com.aibox.infra.plugin.PluginManager] / [com.aibox.infra.benchmark.BenchmarkStorage]。
     */
    fun create(container: AppContainer): SearchEngine {
        val providers: List<SearchProvider> = listOf(
            ChatSearchProvider(container.chatRepository),
            ChatMessageSearchProvider(container.chatRepository),
            ModelSearchProvider(container.modelRepository),
            SettingSearchProvider(),
            ToolSearchProvider(),
            ModuleSearchProvider(container.pluginManager),
            BenchmarkSearchProvider(container.benchmarkSystem.storage)
        )
        LogSystem.info(TAG, "SearchEngine created with ${providers.size} providers: " +
            providers.joinToString { it.getDomain().name })
        return SearchEngine(providers)
    }
}
