# 变更记录 2026-11-21 · 流式光标位置修正 + 思考过程 Markdown 渲染

## 背景

上一版（v261120c-stream-smooth）交付后，用户反馈两点：

1. 打字机呼吸光标「在该行末尾而非文字末尾」。
2. 希望思考过程（`<think>` 折叠块展开后）也能渲染 Markdown。

## 改动

### 1. 呼吸光标回到文字末尾

- 文件：`app/src/main/kotlin/com/aibox/ui/chat/MarkdownText.kt`
- 原因：流式路径用 `Row + Text(modifier.weight(1f, fill=true)) + 独立光标 Text`，`weight` 把正文撑满整行，独立光标被顶到「行尾」，长文本折行时看起来像停在输入框末尾。
- 修法：流式正文改为单个 `Text`，把光标用 `AnnotatedString + SpanStyle` 直接拼在文本尾部；光标随文字一起折行，永远落在「最后一个字」之后。
- 抽出 `rememberCaretAlpha()` 只返回呼吸透明度（1f↔0.15f / 520ms），「减弱动态效果」开启时不调用、不跑动画。

### 2. 思考过程支持 Markdown

- 文件：同上，`ThinkingSection` 展开正文由纯 `Text` 改为 `MarkdownText`。
- 效果：思考内容里的标题、列表、加粗/斜体、行内代码、围栏代码块、引用、链接等都能渲染。
- 流式期间复用 `MarkdownText` 的轻量纯文本路径（生成中逐 token 不重解析），结束再切完整 Markdown；字号 13sp / 行高 19 比正文略小，沿用 dim 色与淡竖线。
- `choicesEnabled=false`、`showCaret=false`：思考内容里不渲染选项按钮、不挂光标；无递归（Markdown 块不含 think 块）。

## 未改动

- 打字机缓冲、BDE、减弱动态开关等上一版逻辑保持不变。
- 不引入蓝色强调色；主题/配色沿用现有中性风格。

## 验证

- 直接提交 main，优先 hosted NPU job 编译并交付 APK（见 tag / Pre-release）。
