# 变更记录 2026-11-21 · 温度双卡 + 默认最新消息 + DeepSeek 新版全量 zip 流式导入

## 背景

用户反馈与诉求：

1. 设备状态页温度拆成"原始温度 / 体感温度"左右两卡；"透心凉/冰冰冰"两档经评估不加（真机已实测 19℃，但属结温/电池兜底，冷档无实际语义），改为把体感温度显式呈现。
2. 默认进入会话应停在最近消息（当前停在最早）；上滑翻更早消息时，中间偏下加圆形箭头按钮一键返回最近。
3. DeepSeek 等一次性导出全量数据（新版 zip：`chat_sessions/` 每会话一文件夹 + `chat_session_index.json` + `manifest.json` + `user.json`）。"拆分导出/减少数据"不可行，要求能完整导入、不拆包、不减数据、整包不入内存。
4. 结合 UI 体验优化（未读到项灰显、电量温度并入温度区、文案统一）。

## 改动

### 1. 设备状态页：原始温度 / 体感温度双卡（`ThermalMonitorScreen.kt`）

- 温度区块从单卡改为 `Row` + 两个 `weight(1f)` 的 `UnifiedCard`：
  - 左卡"原始温度"：`celsius`（结温，驱动降频/热档位）+ `skinFeel(celsius)` 七档文案 + 热度百分比。
  - 右卡"体感温度"：`skinCelsius`（机身表面，读不到时电池兜底）+ `skinFeel(skinCelsius)` 七档文案 + 数值；读不到显示"—"，**绝不拿结温冒充体感**。
- 新增订阅 `monitor.skinCelsius`（此前本页未采集体感）。
- 两卡文案统一到 `skinFeel()` 七档（与聊天页 StatusPill 一致），不再用旧的四档 `stateLabel`。
- "明细"里"未读到 / — / 无数据"值灰显（alpha 0.5），CPU/GPU 未读项不再刺眼。
- 电量区块的电池温度并入右卡展示（作为体感来源提示）。

### 2. 聊天页：默认最新 + 回到底部悬浮按钮（`ChatScreen.kt`）

- 根因：唯一的自动滚底 effect 被 `nearBottom` 门控，首次进长会话 `lastVisible≈0` 不满足条件 → 停在最早消息。
- 新增 `pendingJumpToBottom` 标志：`LaunchedEffect(sessionId)` 置位，消息加载出来后（count>1）一次性 `scrollToItem(last)` 瞬时落底，避免进页飞滚。
- 悬浮按钮：消息区 `Box` 内 `AnimatedVisibility`（`Alignment.BottomCenter` + padding 28dp）+ 圆形 `FloatingActionButton`（`KeyboardArrowDown`），`derivedStateOf` 检测"离底部超过 3 条"时显示；点击 `animateScrollToItem(last)`，减弱动态时用瞬时滚动；流式生成期间隐藏（自动跟随中，避免抢滚动）。
- 与原有流式跟随 effect 协同：按钮只在用户主动上滑后出现，不干扰打字机逐 token 跟随。

### 3. DeepSeek 新版全量 zip 流式导入（新增 `DeepSeekZipImporter.kt`）

- 新文件 `data/backup/DeepSeekZipImporter.kt`：两遍流式扫描 zip：
  - 第一遍只读 `chat_session_index.json` 建立 `id→标题` 映射，并统计会话文件夹数（进度 total）。
  - 第二遍按 `chat_sessions/<uuid>/` 分组聚合多个 json，换文件夹时一次性解析落库。
- **峰值内存 = 单个会话文件的 JSON 树**，而非整包 300+MB；不拆包、不减数据、不整包入内存，直接回应用户"拆分不行、减少做不到"的结论。
- 消息字段自适应（DS 各版本漂移）：role 兼容 role/sender/author/type/from（REQUEST/user/human→user，其余→assistant）；content 兼容 content/text/message/body/value 及 parts/fragments 嵌套；ts 兼容毫秒/秒/ISO。认不出的结构记 warning 跳过，不中断整包。
- `BackupRepository.importDeepSeekZip(openStream, onProgress)` 包装入口；`DataManagementViewModel` 按文件魔数 `PK\x03\x04` 分流：zip 走流式（不受 150MB 文本闸门限制），非 zip 走原文本路径；导入进度写入 `UiState.importProgress`。
- UI：导入说明加"DeepSeek 新版全量 zip"，文件选择器加 `application/zip`，按钮显示 `x/y 会话` 进度。

说明：DS 新版内部消息字段无公开稳定契约，解析器按常见字段宽容探测；若实际导出字段差异较大，导入结果会给出 warning，可按用户提供的真实样例再精调。

## 验证

- hosted NPU job 出包，本轮无 Kotlin/C++ 编译错误（审查确认局部 suspend 函数、闭包捕获、扩展函数、Compose API 签名均合法）。
