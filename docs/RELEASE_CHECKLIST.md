# AIBox v261020-1 发布检查清单

本清单用于 v261020-1 pre-release 发布前的完整验证。每项必须有人实测打勾，不能凭"应该没问题"放行。

> **版本号约定**：`v<YYMMDD>-<build>`，如 `v261020-1` 表示 2026-10-20 第 1 次构建。每次发布日期 +1 天，build 号从 1 递增。
>
> 当前 [`app/build.gradle.kts`](../app/build.gradle.kts) 中 `versionName = "v261018-1"`，发布前需更新为 `v261020-1`。

---

## 1. 编译检查

### 1.1 Debug 构建

- [ ] `./gradlew :app:assembleDebug` 编译成功
- [ ] 输出 APK 路径 `app/build/outputs/apk/debug/app-debug.apk`
- [ ] APK 体积在合理范围（< 25 MB；含 native 库）
- [ ] 安装到测试设备能启动

### 1.2 Release 构建

- [ ] `./gradlew :app:assembleRelease` 编译成功
- [ ] 输出 APK 路径 `app/build/outputs/apk/release/app-release.apk`
- [ ] APK 体积在合理范围（< 15 MB；release 包压缩）
- [ ] 签名正常（debug 签名或 release keystore）

### 1.3 多模块

- [ ] 宿主工程 `:app` 模块独立编译通过
- [ ] [`easy-chat-module/`](../easy-chat-module/) 独立编译通过（如有模型注入）
- [ ] [`litert-plugin/`](../litert-plugin/) 独立编译通过（如有 LiteRT-LM SDK 注入）

### 1.4 编译告警

- [ ] Kotlin 编译无 `error:` 级别告警
- [ ] `lint { abortOnError = false; checkReleaseBuilds = false }` 配置已生效
- [ ] 警告级别告警已审阅，可接受

---

## 2. Lint 检查

- [ ] `./gradlew :app:lintDebug` 跑通
- [ ] 无新增 fatal 级别 issue
- [ ] 已知 issue 在 [`app/build.gradle.kts`](../app/build.gradle.kts) `lint` 块中已 `disable` 或注释说明
- [ ] 检查 `Log.i` / `Log.e` 等直接调用是否已迁移到 `LogSystem`（参考 [`P0.1b-migrate-logger`](../../worklog.md) 完成范围）
- [ ] 检查 `import android.util.Log` 残留是否合理（[`InferenceEngine.kt`](../app/src/main/kotlin/com/aibox/domain/inference/InferenceEngine.kt) 与 [`MemoryOptimizer.kt`](../app/src/main/kotlin/com/aibox/domain/inference/MemoryOptimizer.kt) 按约定保留）

---

## 3. ProGuard 检查

参考 [`app/proguard-rules.pro`](../app/proguard-rules.pro)。

- [ ] `IPluginInterface` / `IPluginDescriptor` / `IStreamCallback` / `IPluginLogCallback` 4 个 AIDL interface + Stub + Proxy 不被混淆
- [ ] 7 个 AIDL parcelable（`PluginInfo` / `SettingFieldEntry` / `BackendCapabilitiesParcel` / `LoadModelRequest` / `LoadModelResult` / `InferRequest` / `InferResult`）不被混淆
- [ ] Room 生成的 DAO 实现类不被混淆（Room 自动生成规则已含，但建议显式 keep）
- [ ] DataStore 相关类不被混淆
- [ ] kotlinx.serialization 的 `@Serializable` 类不被混淆
- [ ] `BuildConfig` 字段可访问
- [ ] Native JNI 函数命名正确（`Java_<package>_<Class>_<method>`）
- [ ] 保留注解：`RuntimeVisibleAnnotations` / `Signature` / `Deprecated` / `EnclosingMethod` / `InnerClasses`

**实测方法**：用 `apksigner verify --print-certs` + `jadx-gui` 反编译 release APK，检查 `com.aibox.plugin.*` 类名是否完整。

---

## 4. 多 ABI 检查

参考 [`app/build.gradle.kts`](../app/build.gradle.kts) 第 22-25 行：

```kotlin
ndk {
    abiFilters += listOf("arm64-v8a")
}
```

- [ ] 仅打 `arm64-v8a`（缩小体积，真机几乎都是 arm64）
- [ ] 如果要支持 32 位老设备，加 `armeabi-v7a`（注意：llama.cpp 32 位性能差，建议不支持）
- [ ] 检查 APK 解压后 `lib/` 目录只有 `arm64-v8a/`
- [ ] 检查 `lib/arm64-v8a/` 下含 `libaibox.so`、`libllama.so`、`libggml.so` 等

---

## 5. AndroidManifest 检查

参考 [`app/src/main/AndroidManifest.xml`](../app/src/main/AndroidManifest.xml)。

### 5.1 queries

- [ ] `<queries>` 节点存在
- [ ] 包含 `com.aibox.action.BACKEND_PLUGIN`（让宿主扫到插件 Service）
- [ ] 包含 `com.aibox.action.CHAT_MODULE`（让宿主扫到聊天模块 Service）
- [ ] 包含 `android.intent.action.PACKAGE_ADDED` / `REMOVED` / `REPLACED` / `FULLY_REMOVED` + `data scheme="package"`（让 BroadcastReceiver 能拿到包名）

### 5.2 receiver

- [ ] `com.aibox.infra.plugin.PluginInstallReceiver` 已注册
- [ ] `android:exported="true"`
- [ ] `<intent-filter>` 包含 4 个 `PACKAGE_*` action + `data scheme="package"`

### 5.3 权限

- [ ] 仅申请必要权限：`INTERNET` / `ACCESS_NETWORK_STATE` / `POST_NOTIFICATIONS` / `VIBRATE`
- [ ] 无 `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE`（模型从 HuggingFace 下载到私有目录）
- [ ] 无 `QUERY_ALL_PACKAGES`（用 `<queries>` 替代）
- [ ] 无 `REQUEST_INSTALL_PACKAGES`（除非需要"宿主内安装插件"功能，否则让用户用系统包管理器）

### 5.4 application

- [ ] `android:name=".AIBoxApp"`
- [ ] `android:largeHeap="true"`（大模型推理需要）
- [ ] `android:allowBackup="true"`（让用户能 adb backup）
- [ ] `android:theme="@style/Theme.AIBox"`
- [ ] MainActivity 已声明 + LAUNCHER intent-filter
- [ ] FileProvider 已声明 + `@xml/file_provider_paths`

---

## 6. 模块加载测试

### 6.1 Easy Chat 模块

- [ ] 装入 Easy Chat 模块 APK（含 CI 注入的 485 MB GGUF 模型）
- [ ] 宿主启动后自动发现模块（看日志 `aibox.plugin.manager` 有 `discoverPlugins` 输出）
- [ ] 在"已安装模块"列表能看到 Easy Chat
- [ ] 点击启用，模块进入 Active 状态
- [ ] 选 Easy Chat 提供的模型开始聊天，能正常出字
- [ ] 聊天过程中拔掉模块 APK（系统卸载），宿主不崩
- [ ] 重装模块后，状态恢复到 Discovered

### 6.2 LiteRT-LM 插件

- [ ] 装入 LiteRT-LM 插件 APK（含 CI 注入的 .so 与 TFLite 模型）
- [ ] 宿主启动后自动发现插件
- [ ] 启用插件，在"后端选择"下拉看到 `litert` 选项
- [ ] 加载一份 TFLite 模型，能正常推理
- [ ] 如有 Gemma + Siglip 模型，测一次多模态（输入图像 + 文本 → 文本输出）

### 6.3 失败回退

- [ ] 插件进程 kill（`adb shell am kill com.aibox.litert.backend`）后宿主调用不崩，显示"插件已断开"
- [ ] 插件签名错误（用未签名 APK 安装）应被 `PluginManager.verifySignature` 拒绝启用
- [ ] 插件 `aibox.minVersion` 高于当前宿主版本，应被 `PluginCompatibilityChecker` 拒绝

---

## 7. 基准测试三档

参考 [`BENCHMARK_METHODOLOGY.md`](./BENCHMARK_METHODOLOGY.md)。

### 7.1 基础基准

- [ ] 在 `BenchmarkScreen` 选 Qwen2.5-0.5B 模型 + CPU 后端 + BASIC 矩阵
- [ ] 跑 3 次轻负载（PP=64, TG=32）→ 拿到 TG t/s 中位数
- [ ] 跑 3 次中负载（PP=512, TG=128）→ 拿到 PP t/s 中位数
- [ ] 跑 3 次重负载（PP=1024, TG=256）→ 拿到 TTFT 中位数
- [ ] 结果写入 Room `benchmark_results` 表
- [ ] `EnvironmentSnapshot` 字段完整（SoC / 热档位 / 充电 / 电量）

### 7.2 高级场景

- [ ] 跑 `coldStart`：测 loadModel + TTFT + 总响应
- [ ] 跑 `multiTurn` 10 轮：TTFT 应随 context 增长
- [ ] 跑 `streamingSmoothness` 100 token：记录均值 / 方差 / 最大间隔
- [ ] 跑 `longPrompt` 4096 tokens：测 PP t/s

### 7.3 跨后端对比

- [ ] 选模型 + 多后端（CPU / Vulkan，如有 NPU 加 NPU）
- [ ] 跑 `BackendComparison.compareBackends`
- [ ] 拿到 `ComparisonReport.recommendedBackend` 与"人话版"推荐语
- [ ] 进度条实时更新
- [ ] 某后端失败时其它后端继续

---

## 8. 崩溃日志验证

### 8.1 故意 crash（Java/Kotlin）

- [ ] 在某个 ViewModel 加 `throw RuntimeException("test crash")`
- [ ] 运行触发崩溃
- [ ] 检查 `/sdcard/Android/data/com.aibox/files/aibox/logs/crashes/crash-YYYY-MM-DD_HH-mm-ss.txt` 文件存在
- [ ] 文件含设备信息（SoC / Build / 版本）
- [ ] 文件含 App 状态（已加载模型 / 活跃后端 / 已启用插件）
- [ ] 文件含最近 500 条日志上下文
- [ ] 文件含 stack trace 完整链
- [ ] 移除测试代码

### 8.2 故意 crash（Native 信号）

- [ ] 在某个 cpp 文件加 `int* p = nullptr; *p = 42;`（触发 SIGSEGV）
- [ ] 运行触发崩溃
- [ ] 检查 `/data/data/com.aibox/cache/native_crashes/native_crash_<pid>_<ts>.txt` 文件存在（需 `adb root` 或 debug 包）
- [ ] 文件含 backtrace
- [ ] 移除测试代码

### 8.3 崩溃恢复

- [ ] 崩溃后下次启动，宿主能正常启动（不进入崩溃循环）
- [ ] 启动后能在"日志查看"页看到上次崩溃记录

---

## 9. 多后端切换测试

### 9.1 CPU / Vulkan / NPU / OpenCL

- [ ] 在"设置 → 后端"页能切换后端
- [ ] 切到 CPU，加载模型，能推理
- [ ] 切到 Vulkan，重新加载，能推理
- [ ] 切到 NPU（如有 QNN SDK），重新加载，能推理
- [ ] 切到 OpenCL（如设备有 OpenCL 支持），重新加载，能推理
- [ ] 切换不卸载原后端（让用户能来回切）或按设计卸载

### 9.2 跨后端日志

- [ ] 切换日志走 `LogSystem`，tag 为 `aibox.inference.engine` 或对应 backend 名
- [ ] 切换失败时不崩，显示错误提示

### 9.3 插件后端

- [ ] 启用 LiteRT 插件后，"后端选择"下拉出现 `litert`
- [ ] 选 `litert`，加载 TFLite 模型，能推理
- [ ] 禁用 LiteRT 插件后，`litert` 从下拉消失
- [ ] 当前用 `litert` 推理时禁用插件，应中止推理并提示

---

## 10. 多模态测试

### 10.1 前置条件

- [ ] 已安装 LiteRT-LM 插件（含 Siglip + Gemma TFLite 模型）
- [ ] 在"设置 → 插件 → LiteRT"开启"启用视觉编码器"

### 10.2 图像 + 文本

- [ ] 在聊天页能选择图片输入（如相机 / 相册）
- [ ] 输入一张图 + 一段文字（如"这张图里有什么？"）
- [ ] 模型返回对图像的描述
- [ ] 日志中 `aibox.plugin.litert.binder` 显示 `multimodal infer` 路径

### 10.3 纯文本（控制组）

- [ ] 关闭"启用视觉编码器"开关
- [ ] 同一份模型只输入文本，能正常推理
- [ ] 性能与多模态对比，纯文本 TG 应略高

### 10.4 无多模态插件时

- [ ] 未装 LiteRT 插件时，聊天页不出现"图像输入"按钮
- [ ] 或按钮置灰，点击提示"需要安装多模态插件"

---

## 11. 国际化

> **当前状态**：[`app/src/main/res/`](../app/src/main/res/) 仅 `values`（中文）+ `values-en`（英文），`locales_config.xml` 仅声明 `zh` + `en`。**7 语言是 v261020-1 之后的扩展目标**。

### 11.1 已有语言

- [ ] 中文（默认）所有 UI 文案有 `strings.xml`
- [ ] 英文 `values-en/strings.xml` 全覆盖
- [ ] 切换系统语言到英文，UI 跟随切换

### 11.2 计划新增语言（v261020+ 扩展目标）

- [ ] `values-ja`（日文）
- [ ] `values-ko`（韩文）
- [ ] `values-fr`（法文）
- [ ] `values-de`（德文）
- [ ] `values-ru`（俄文）
- [ ] 加到 `locales_config.xml`
- [ ] 在 `build.gradle.kts` `bundle.language.enableSplit = true` 已生效

### 11.3 RTL

- [ ] 阿拉伯语等 RTL 语言如计划支持，加 `values-ar` 并测试
- [ ] 当前 `android:supportsRtl="true"` 已声明

---

## 12. 性能验证

参考 [`PERFORMANCE_TUNING.md`](./PERFORMANCE_TUNING.md) 与 [`BENCHMARK_METHODOLOGY.md`](./BENCHMARK_METHODOLOGY.md)。

### 12.1 TTFT

- [ ] 默认档：TTFT < 3000 ms（Qwen2.5-0.5B / 骁龙 8 Gen3 CPU 2 线程）
- [ ] 性能档：TTFT < 1000 ms
- [ ] 极限档（开所有优化 + 预加载）：TTFT < 500 ms

### 12.2 TG

- [ ] 默认档：TG > 25 t/s（Qwen2.5-0.5B / 骁龙 8 Gen3 CPU 2 线程）
- [ ] 性能档：TG > 30 t/s
- [ ] NPU 后端：TG > 80 t/s（如有 QNN SDK）

### 12.3 内存

- [ ] 0.5B 模型峰值内存 < 800 MB
- [ ] 1.5B 模型峰值内存 < 1.6 GB
- [ ] 长时间使用（30 分钟）无内存泄漏（用 Android Profiler 观察）

### 12.4 流式平滑度

- [ ] CPU 后端：100 token 间隔方差 < 30 ms²
- [ ] Vulkan 后端：方差 < 15 ms²
- [ ] 无 GC 抖动导致的偶发 200+ ms 间隔

---

## 13. 体积检查

- [ ] Release APK 体积 < 15 MB（不含模型）
- [ ] Debug APK 体积 < 25 MB
- [ ] ABI 拆分后单 ABI APK < 12 MB
- [ ] 用 [APK Analyzer](https://developer.android.com/studio/build/apk-analyzer) 检查：
  - `lib/arm64-v8a/` 占比合理（不应 > 60%）
  - `classes.dex` 占比合理（不应 > 20%）
  - `res/` 占比合理（不应 > 15%）
  - `assets/` 不含模型（模型由用户下载或模块 APK 提供）
- [ ] 资源压缩已开（`isShrinkResources = true`）
- [ ] `META-INF` 排除项已配（参考 [`app/build.gradle.kts`](../app/build.gradle.kts) 第 95-108 行）

---

## 14. 隐私合规

### 14.1 无网络请求声明

- [ ] App 主功能（推理）不需要网络
- [ ] 仅"下载模型"功能用网络，且明确标注"下载源"（HuggingFace / ModelScope / HF Mirror）
- [ ] 无埋点统计
- [ ] 无 Crash 上报到第三方（崩溃只写本地文件）
- [ ] 无广告 SDK

### 14.2 数据存储

- [ ] 所有数据存到 `/sdcard/Android/data/com.aibox/files/`（应用私有目录，卸载自动清理）
- [ ] 模型文件不读写外部存储（无 `READ_EXTERNAL_STORAGE`）
- [ ] 用户对话历史存 Room，加密可选（暂未实现）
- [ ] 日志不包含敏感信息（如 API key）

### 14.3 权限说明

- [ ] `INTERNET`：仅用于下载模型（用户主动触发）
- [ ] `ACCESS_NETWORK_STATE`：判断网络可用性
- [ ] `POST_NOTIFICATIONS`：下载完成通知（Android 13+）
- [ ] `VIBRATE`：下载完成震动反馈

### 14.4 隐私政策

- [ ] README 或 `PRIVACY.md` 说明数据收集范围
- [ ] 如上架 Google Play，填好 Data Safety 表
- [ ] 如上架国内市场，准备隐私政策 URL

---

## 15. License 检查

参考 [`LICENSE`](../LICENSE)。

### 15.1 宿主工程

- [ ] Apache 2.0
- [ ] `LICENSE` 文件存在
- [ ] 所有源文件头无强制 copyright（按 Apache 2.0 不强制）
- [ ] `NOTICE` 文件（如有）列举第三方组件

### 15.2 llama.cpp

- [ ] llama.cpp 是 MIT License
- [ ] 在 `NOTICE` 或 README 标注
- [ ] llama.cpp 源码作为子模块链接，未修改（如修改，标注 patch 文件）
- [ ] 编译后的 `libllama.so` / `libggml.so` 不违反 MIT

### 15.3 第三方库

- [ ] Compose BOM (Apache 2.0)
- [ ] Material3 (Apache 2.0)
- [ ] Room (Apache 2.0)
- [ ] DataStore (Apache 2.0)
- [ ] kotlinx-coroutines (Apache 2.0)
- [ ] kotlinx-serialization (Apache 2.0)
- [ ] OkHttp (Apache 2.0)
- [ ] Retrofit (Apache 2.0)
- [ ] Moshi (Apache 2.0)
- [ ] WorkManager (Apache 2.0)
- [ ] Navigation Compose (Apache 2.0)
- [ ] 所有依赖 License 兼容 Apache 2.0

### 15.4 模块 / 插件

- [ ] Easy Chat 模块：MIT
- [ ] LiteRT-LM 插件：Apache 2.0
- [ ] 各模块 README 标注 License

---

## 16. Release Notes 编写

### 16.1 模板

```markdown
# AIBox v261020-1

## 新功能

- **插件系统**：支持第三方插件 APK，通过 AIDL Binder 与宿主通信
  - 新增 [`PLUGIN_DEVELOPMENT.md`](docs/PLUGIN_DEVELOPMENT.md) 完整插件开发指南
  - 示例：[`easy-chat-module/`](easy-chat-module/)（聊天模块）、[`litert-plugin/`](litert-plugin/)（推理框架后端）
- **基准测试**：自建三层基准测试（基础 / 高级 / 跨后端），不依赖第三方 App 数据
  - 新增 [`BENCHMARK_METHODOLOGY.md`](docs/BENCHMARK_METHODOLOGY.md) 方法论
- **优化系统**：TTFT / TG / 能耗三个维度的开关式优化，三档预置（默认 / 性能 / 省电）
  - 新增 [`PERFORMANCE_TUNING.md`](docs/PERFORMANCE_TUNING.md) 调优指南
- **日志系统**：RingBuffer + 异步刷盘 + Java/Native 崩溃捕获 + 会话独立日志
- **UI 统一**：Design Token + 6 个统一组件 + Schema 渲染引擎，插件 UI 自动生成

## 改进

- 重构推理抽象层 V2，支持多模态 / 多任务 / 多框架
- 零异常约定（Outcome 类型），所有跨进程 / 跨模块错误用值表达

## 文档

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- [`docs/PLUGIN_DEVELOPMENT.md`](docs/PLUGIN_DEVELOPMENT.md)
- [`docs/BENCHMARK_METHODOLOGY.md`](docs/BENCHMARK_METHODOLOGY.md)
- [`docs/PERFORMANCE_TUNING.md`](docs/PERFORMANCE_TUNING.md)
- [`docs/RELEASE_CHECKLIST.md`](docs/RELEASE_CHECKLIST.md)

## 已知问题

- 仅支持中文 / 英文（7 语言为后续扩展目标）
- V1 与 V2 推理抽象层共存，V1 调用方未迁移到 V2
- LiteRT 插件需要 CI 注入 LiteRT-LM SDK 与 QNN SDK，源码仓库不含 .so

## 下载

- AIBox-v261020-1-arm64-v8a.apk (约 12 MB)
- easy-chat-module-1.0.0.apk (约 485 MB，含 Qwen2.5-0.5B GGUF)
- litert-plugin-1.0.0.apk (约 5 MB，不含 .so，需另行下载 SDK)

## License

Apache 2.0 (宿主)；MIT (Easy Chat 模块)；Apache 2.0 (LiteRT 插件)；MIT (llama.cpp)
```

### 16.2 发布前检查

- [ ] Release Notes 模板填充完整
- [ ] APK 下载链接正确
- [ ] sha256 校验值列出（用户能验证下载完整性）
- [ ] 标注 pre-release（不是 stable）
- [ ] 标注最低 Android 版本（API 28+）
- [ ] 标注推荐设备能力（low_end / mid_range / flagship）

---

## 17. 最终发布步骤

### 17.1 准备

- [ ] 更新 [`app/build.gradle.kts`](../app/build.gradle.kts) `versionName = "v261020-1"`、`versionCode` 递增
- [ ] 更新 [`README.md`](../README.md) 版本号
- [ ] git commit 所有改动
- [ ] git tag `v261020-1`
- [ ] git push tag

### 17.2 构建

- [ ] CI 触发 release 构建（如配置了 GitHub Actions）
- [ ] 或本地 `./gradlew :app:assembleRelease`
- [ ] 用 release keystore 签名

### 17.3 发布

- [ ] 在 GitHub 创建 Release `v261020-1`
- [ ] 上传 APK（含 sha256）
- [ ] 粘贴 Release Notes
- [ ] 标注 pre-release
- [ ] 公开发布

### 17.4 发布后

- [ ] 安装到测试设备验证（模拟用户首次安装流程）
- [ ] 观察 24h 崩溃率（如能收集）
- [ ] 收集用户反馈，列入 v261021-1 计划

---

## 18. 例外与回滚

### 18.1 发现阻断性 bug

- [ ] 立即撤回 GitHub Release（标记为 draft）
- [ ] 修复 + 重测
- [ ] 版本号递增（如 v261020-2）
- [ ] 重新发布

### 18.2 已发布用户受影响

- [ ] 在 README 顶部加"已知问题"提示
- [ ] 在下个版本（v261021-1）修复
- [ ] 重大问题可发布 hotfix（v261020-2）

---

## 19. 检查清单签字

- [ ] 编译检查：____________（人 + 日期）
- [ ] Lint 检查：____________
- [ ] ProGuard 检查：____________
- [ ] 多 ABI 检查：____________
- [ ] AndroidManifest 检查：____________
- [ ] 模块加载测试：____________
- [ ] 基准测试三档：____________
- [ ] 崩溃日志验证：____________
- [ ] 多后端切换测试：____________
- [ ] 多模态测试：____________
- [ ] 国际化：____________
- [ ] 性能验证：____________
- [ ] 体积检查：____________
- [ ] 隐私合规：____________
- [ ] License 检查：____________
- [ ] Release Notes：____________
- [ ] **最终批准发布**：____________（维护者）

---

## 20. 参考实现索引

| 章节 | 参考代码 / 文档 |
| --- | --- |
| 编译 / ProGuard | [`app/build.gradle.kts`](../app/build.gradle.kts) + [`app/proguard-rules.pro`](../app/proguard-rules.pro) |
| AndroidManifest | [`app/src/main/AndroidManifest.xml`](../app/src/main/AndroidManifest.xml) |
| 插件系统 | [`app/src/main/kotlin/com/aibox/infra/plugin/`](../app/src/main/kotlin/com/aibox/infra/plugin/) + [`docs/PLUGIN_DEVELOPMENT.md`](./PLUGIN_DEVELOPMENT.md) |
| 基准测试 | [`app/src/main/kotlin/com/aibox/infra/benchmark/`](../app/src/main/kotlin/com/aibox/infra/benchmark/) + [`docs/BENCHMARK_METHODOLOGY.md`](./BENCHMARK_METHODOLOGY.md) |
| 优化系统 | [`app/src/main/kotlin/com/aibox/domain/optimization/`](../app/src/main/kotlin/com/aibox/domain/optimization/) + [`docs/PERFORMANCE_TUNING.md`](./PERFORMANCE_TUNING.md) |
| 日志系统 | [`app/src/main/kotlin/com/aibox/util/log/`](../app/src/main/kotlin/com/aibox/util/log/) + [`app/src/main/kotlin/com/aibox/infra/crash/`](../app/src/main/kotlin/com/aibox/infra/crash/) |
| 架构总览 | [`docs/ARCHITECTURE.md`](./ARCHITECTURE.md) |
