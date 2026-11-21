# 变更记录 2026-11-21：聊天分支 / 页内查找 / 工具入口 / GPS 增强 / AI 标题 / DS 导入修复

本批 6 项功能，全部直接提交 `main`，不开分支。

## 1. 聊天分支功能增强
- `ChatViewModel.regenerateAt(target)`：可对会话里**任意一条助手消息**重新生成，旧回复作为 `versions` 保留，新回复追加为新版本，消息下方版本切换器即可在各分支间切换；上下文只取该消息之前的可见消息，符合“从此处分支”语义。生成失败会把原消息放回，避免分支点消失。
- `ChatScreen`：任意助手消息长按菜单新增“从此处分支”；版本切换器加“分支”语义标签。
- 说明：用户消息分支仍由既有的“编辑 + 版本切换”负责；两者共同构成分支能力。

## 2. 聊天页内查找
- 顶栏搜索图标改为**页内查找**：输入关键字，在当前会话消息（正文 + 思考 + 工具输出）里定位命中项，命中项高亮描边，支持上一条/下一条跳转、命中计数。查找时暂停自动跟随底部。
- 原“全局搜索所有会话”保留在顶栏“更多”菜单里。

## 3. 聊天页工具入口
- 加号面板新增“工具”行（显示已启用数量），点击跳转工具设置页。新增 `ChatScreen.onOpenToolSettings` 回调，导航层已接入。

## 4. GPS 位置工具增强（不新增权限）
- 基于定位数据自身字段附带：海拔（明确标注 WGS84 椭球高，非气压高度）、垂直精度、速度（m/s + km/h）、方位角；数据没有的字段一律不显示，不推算、不冒充。
- `structuredData` 同步增加 `altitude / altitude_ref / vertical_accuracy / speed_mps / speed_kph / bearing_deg`。

## 5. AI 自动写会话标题（全局开关）
- 新增全局开关（设置 → 对话 → “AI 自动写会话标题”，默认开启），DataStore key `ai_auto_title`。
- 仅**云端模型 + 首次建会话**时触发一次：首条消息发出后异步调用一次非流式短请求（`OpenAiCompatibleClient.chatOnce`，`stream=false`、小 `max_tokens`、不带工具）生成短标题。
- 失败降级为默认标题；写回前再次核对会话标题仍等于默认值，**绝不覆盖用户已手动改过的标题**；本地模型完全不触发。

## 6. DeepSeek 全量 zip 导入修复（基于脱敏样例核对真实结构）
- **路径修正**：真实结构是 `chat_sessions/<uuid>.json`（单文件单会话）+ `chat_sessions/<uuid>_files/*.webp`，旧版 `<uuid>/xxx.json` 兼容。
- **时间**：会话用 `inserted_at/updated_at`，消息用各自 `inserted_at`（ISO-8601），落库后校正消息数；历史列表按真实时间排序。
- **形式**：`THINK` → 思考段，`RESPONSE` → 正文段；带思考/工具时保留 DS 原有交错时间线（正文也进 parts，避免 UI 只渲染 parts 导致正文消失）。
- **工具片段**：`TOOL_SEARCH` → web_search 工具段（queries + results → hits）；`TOOL_OPEN` → url_reader 工具段（url + snippet）。
- **图片附件**：`FILE` 中 is_image 的文件字节从 zip 落到应用私有 attachments 目录，生成 `ChatAttachment(kind="image")`；落地失败留文本占位。
- **消息树分支**：按 `parent_id` 从 `current_message_id` 回溯当前分支链落库；同一 parent 下的其它用户分支作为该用户消息的 `versions`，可用版本切换器查看。
- 无正文片段（`CONTENT_RISK`/`TIP` 等）丢弃；单会话异常不中断整包导入。

## 涉及文件
- `data/backup/DeepSeekZipImporter.kt`（重写）、`data/repo/ChatRepository.kt`、`data/repo/SettingsRepository.kt`
- `domain/cloud/OpenAiCompatibleClient.kt`、`domain/cloud/CloudChatService.kt`
- `domain/tool/builtin/GpsLocationTool.kt`
- `ui/chat/ChatViewModel.kt`、`ui/chat/ChatScreen.kt`、`ui/nav/AppNav.kt`
- `ui/settings/SettingsViewModel.kt`、`ui/settings/SettingsScreen.kt`
