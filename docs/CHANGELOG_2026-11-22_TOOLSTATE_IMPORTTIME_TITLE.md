# 变更记录 2026-11-22：工具状态卡死修复 + 导入时间保真 + 标题生成器完善

> 本批三项：①修复"第一次工具一直显示正在调用、回答结束才一次性刷出"的 bug；②所有数据导入路径保留数据里标注的时间，会话列表不再显示导入时刻；③完善云端 AI 自动标题的 prompt 与清洗，消除"我们需要回答用户…"式啰嗦输出。

---

## 1. 工具调用状态卡死（bug 修复）

### 现象
模型第一次调用工具时，"正在调用"转圈持续很久；实际上工具早已执行完、模型已继续思考并调用更多工具，直到整段回答结束所有工具状态才一次性显示。

### 根因（三层叠加）
1. **主线程被独占**：`generate()` 跑在 `inferenceScope = Dispatchers.Main.immediate`。工具执行链 `executeAwait → runInvocation → ToolExecutor → ToolRegistry → tool.execute()` 默认在调用方线程同步跑完（12 个内置工具仅 4 个自带 IO 调度）。从 push `running=true` 到 push `running=false` 之间没有真正的调度让出点，Compose 来不及重组观察中间态。
2. **双来源竞争**：工具状态有两套系统——`ToolInvocationViewModel.invocations`（4 态状态机，实时正确）被 UI 明确弃用（`ChatScreen` 注释：避免重复卡片），UI 唯一渲染源是 `MsgPart.running`（`ToolPartRow` 的转圈）。后者更新不可靠。
3. **首轮二次解析**：首轮 `baseLen=0`，打字机 `parseTimeline(current)` 会把模型输出的 `aibox:tool` 块二次解析出无结果的 tool part，与手动维护的 toolPart 竞争覆盖，状态来回跳。

### 修复（ChatViewModel.kt）
- push `running=true` 后加 `yield()`，确保 Compose 渲染出"正在调用"这一帧。
- 工具执行包进 `withContext(Dispatchers.IO)`，慢工具不再占用主线程。
- 打字机（含减弱动态模式）emit 时对 `parseTimeline` 结果 `filterNot { KIND_TOOL }`，工具卡只由 `parts` 里手动维护的 toolPart 表达，消除双来源竞争。

---

## 2. 导入数据的时间保真（bug 修复）

### 现象/根因
`ChatRepository.addMessage` 每次落消息都把会话 `lastUsedAt` 重置为 `System.currentTimeMillis()`，而会话列表恰好按 `lastUsedAt` 显示与排序（`HistoryScreen:288`、`Daos:34`）。因此：
- DeepSeek zip 导入虽用 `createSessionAt` 写入了数据时间，但第一条消息落库就被冲成导入时刻；
- AIBox 原生备份导入、外部聊天导入连会话创建都用了 `createSession`（now），原始时间全丢。

### 修复
新增三个导入专用能力（ChatRepository.kt）：
- `addMessageAt`：落消息只增 messageCount，不重置会话时间；
- `setSessionTimes`：导入完成后把 createdAt/lastUsedAt 定稿为数据值；
- `createSessionAt` 扩展支持 modelPath / characterId / personaId。

三个导入路径统一改造：
- **DeepSeek zip**：`addMessageAt` + `setSessionTimes(createdAt, updatedAt)`。
- **AIBox 原生备份**：用 `SessionBackup.createdAt/lastUsedAt` 创建会话（0 才回落 now），`addMessageAt` + 定稿，保留 modelPath。
- **外部聊天（ChatGPT/DeepSeek 文本）**：导出无独立会话时间字段，用消息 ts 推导 createdAt=最早、lastUsedAt=最晚；都没有才用 now。消息 ts 原样保留。

现在会话列表显示/排序的时间一律来自数据里标注的时间，不再是导入时刻。

---

## 3. 标题生成器完善

### 现象
云端 AI 自动标题产出"我们需要回答用户。用户说"XXX"。需要作为标题"这类啰嗦输出——推理型模型把"起标题"当成待分析问题，吐出了思考过程。

### 修复（CloudChatService.kt）
- 新增 `TITLE_SYSTEM_PROMPT`：few-shot（3 组"内容→标题"示例）+ 四条硬规则，把输出锚定成"一个名词短语，≤12 汉字"，明确禁止分析/复述/前缀。
- user 消息改为"给这段对话起标题：\n<内容>"，语义更明确。
- 新增 `sanitizeTitle` 后处理：剥离 `<think>` 思考块、取第一行有效文本、去"标题："/列表符号/包裹引号；对含"用户说/回答用户/需要作为标题/起标题"等复述模板的输出直接判失败降级为默认标题，绝不把噪声当标题写回。
- maxTokens 32 → 48（容纳 few-shot 场景下的短标题），仍保持短请求。

---

## 验证
- 待 CI 构建通过后交付 APK。
- 三项均为逻辑/提示词改动，不涉及新权限、不改数据格式契约（备份格式仍 v1，时间字段本就在导出内）。
