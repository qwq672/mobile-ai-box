# 2026-11-20 流式输出流畅化 + 减弱动态效果开关

## 目标

解决「流式输出一顿一顿、不优雅」的观感，并给出可控开关、降低重组/发热。用户定调：**A/C 优先做扎实，B/D/E 适度**，附带设置开关、UI/后端/速度/功耗优化与 bug 检查。

## A. 打字机缓冲（核心）

- 新增 `StreamTypewriter`（`ChatViewModel.kt`）：token 先写入 `@Volatile target`，独立协程在 Main 作用域按 **20ms / 3 字**（≈150 字/s）匀速往外吐，把 NPU 上 70~150ms 一个 token 的顿挫铺平成匀速流动。该速度高于任何端侧出字速率，只会「铺平」不会引入可观滞后。
- 多轮工具调用修复：`reset(alreadyShown)` 传入已完成轮次前缀长度，跳过旧文本，避免第二轮起「重播」上一轮正文造成约 0.7s 延迟。
- 收尾 `flush()` + `finally { stop() }`：每轮结束把缓冲剩余一次性推完，异常/break 都停协程，杜绝残留中间态覆盖与协程泄漏。
- 分段解析：`emit` 只对本轮新增正文 `parseTimeline`，不重复解析已完成轮次的工具卡/思考块。

## C. 呼吸光标

- `MarkdownText` 新增 `showCaret`：流式纯文本末尾渲染 `▌`，`rememberInfiniteTransition` 在 1f↔0.15f / 520ms 呼吸明灭，表明「还在写」。
- 只挂在最后一段正文末尾；开启减弱动态时彻底关闭。

## B/D/E（适度）

- **B**：流式期间改纯文本直出，跳过 `parseBlocks + parseInline` 的逐块正则与 AnnotatedString 构建；生成结束（streaming=false）再切回完整 Markdown（含 Mermaid 兜底）。
- **D**：自动滚动区分「逐 token 跟随」（瞬时，避免动画被打断反而更抖）与「新增消息一次性跳动」（180ms tween 平滑落位）；用户上滑时不抢滚动；减弱动态时一律瞬时。
- **E**：原 33ms 合批保留在减弱动态（直出）路径；默认路径由打字机节奏接管。

## 减弱动态效果开关

- `SettingsRepository` 新增 `REDUCE_MOTION` DataStore key + `reduceMotion` Flow + `setReduceMotion`。
- `SettingsViewModel` 状态/订阅/`onReduceMotionChange`；`SettingsScreen`「外观」分组新增开关（默认关闭）。
- `SettingRegistry` 新增搜索项（外观 > 动态效果）。
- 开启后：打字机关闭（token 直出）、呼吸光标关闭、height 过渡关闭、平滑滚动关闭、跳跳点改静态三连点。遵循现有中性/黑白主题，不引入蓝色强调色。

## Bug 检查 / 速度 / 功耗

- 打字机把 UI 更新从「每 token」降到固定节奏；流式期间不做 Markdown 全量解析，长文本后段不再掉帧，CPU 占用与发热下降。
- 滚动基线 `lastKnownItemCount` 无条件更新，避免上滑期间跳过的变化在回到底部后误触发动画。
- `reduceMotion` 关闭所有无限循环动画，进一步省电。
- 所有改动函数（MessageItem / AssistantParts / ThinkingIndicator / MarkdownText）均用命名参数，调用点已逐一核对，无遗漏。

## 关联：本轮早前的崩溃 / 容量分析（未改代码）

- 两份 `native_crash_*.txt` 为 SIGABRT（无符号裸 PC）；根因在新日志的 tombstone：Hexagon v75 `fastrpc_mmap failed`（约 1GB 映射，`DMA64 disabled` → 32 位 IOVA 撞窗），C++ 异常未捕获触发 `abort()`。
- 结论：~3.5GB 是 v75 实用窗口（4GiB 为 32 位理论顶）；4.19GiB 的 Ling-3.0-tiny-Q4_0 只在 load 阶段「假装成功」，首次 prefill 穿窗崩溃；多会话对映射层对症但救不了物理超限，QNN 多会话才是另一条路。
- ONNX：项目已有路由/插件槽位与示例 manifest，只差真实 runtime；按用户要求暂缓，未展开。

## 交付

- 直接提交 `main`（不开分支），hosted NPU job 编译验证，产物 tag/Pre-release 与 APK 见当次交付记录。
