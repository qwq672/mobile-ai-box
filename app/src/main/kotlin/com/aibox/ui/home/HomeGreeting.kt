package com.aibox.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aibox.ui.theme.Colors
import com.aibox.ui.theme.DesignTokens
import com.aibox.ui.theme.Type
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 主页问候卡片。
 *
 * 三段内容各自独立随机（避免每次回到主页都看到同一句）：
 * 1. 时段问候（早上好 / 中午好 / 傍晚好 …），只从当前时段对应的语料池里取；
 * 2. 子问候（今天吃了没 / 该睡了 …）；
 * 3. 一条轻量建议（可以让我帮你做的事）。
 *
 * 轮换策略：进入页面时随机抽一次，三段互不绑定；**没有定时器、不做定时轮播**。
 * 刷新本页（重新进入组合）即可换一批。这样既不会盯着屏幕等它变，也不会整组翻页。
 */
@Composable
internal fun GreetingCard(modifier: Modifier = Modifier) {
    val now = LocalDateTime.now()
    val period = periodOf(now.hour)
    val greetings = GREETINGS.getValue(period)
    // 三条各自独立随机一次；key 含 period，跨时段会重抽。无定时器。
    val greeting = remember(period) { greetings.random() }
    val sub = remember(period) { SUB_GREETINGS.random() }
    val tip = remember(period) { TIPS.random() }

    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        Colors.primary.copy(alpha = 0.22f),
                        Colors.tertiary.copy(alpha = 0.10f),
                        Colors.surfaceContainerLow
                    )
                )
            )
            .border(1.dp, Colors.primary.copy(alpha = 0.15f), shape)
            .padding(horizontal = DesignTokens.spacing16, vertical = 18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = dateLabel(now.toLocalDate()),
                    style = Type.labelSmall,
                    color = Colors.primary,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(6.dp))
                Crossfade(targetState = greeting, animationSpec = tween(400), label = "greeting") { text ->
                    Text(
                        text = text,
                        style = Type.titleLarge,
                        color = Colors.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(4.dp))
                Crossfade(targetState = sub, animationSpec = tween(400), label = "sub_greeting") { text ->
                    Text(
                        text = text,
                        style = Type.bodyMedium,
                        color = Colors.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(DesignTokens.spacing12))
                // 建议：半透明胶囊，视觉上比正文轻一档
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Colors.surface.copy(alpha = 0.55f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Crossfade(targetState = tip, animationSpec = tween(400), label = "tip") { text ->
                        Text(
                            text = "✨ $text",
                            style = Type.bodySmall,
                            color = Colors.onSurface
                        )
                    }
                }
            }
            Spacer(Modifier.width(DesignTokens.spacing12))
            // 右侧时段图标：圆形淡色底，让卡片右边不至于空
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(Colors.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = period.emoji, style = Type.headingMedium)
            }
        }
    }
}

/* ============================================================
 * 语料
 * ============================================================ */

private enum class DayPeriod(val emoji: String) {
    MORNING("🌅"),
    NOON("☀️"),
    AFTERNOON("🌤️"),
    EVENING("🌆"),
    NIGHT("🌙")
}

private fun periodOf(hour: Int): DayPeriod = when (hour) {
    in 5..10 -> DayPeriod.MORNING
    in 11..13 -> DayPeriod.NOON
    in 14..17 -> DayPeriod.AFTERNOON
    in 18..22 -> DayPeriod.EVENING
    else -> DayPeriod.NIGHT
}

/** 每个时段 7 条，共 35 条。 */
private val GREETINGS: Map<DayPeriod, List<String>> = mapOf(
    DayPeriod.MORNING to listOf(
        "早上好", "早安", "早上好呀", "新的一天，早上好",
        "清晨好", "早啊，精神点", "又是元气满满的一天"
    ),
    DayPeriod.NOON to listOf(
        "中午好", "午安", "中午好，吃饭了吗", "正午好",
        "中午好，歇一会儿吧", "饭点到了，中午好", "午后将至，中午好"
    ),
    DayPeriod.AFTERNOON to listOf(
        "下午好", "下午好呀", "午后好", "下午好，来杯水吧",
        "阳光正好，下午好", "下午好，慢慢来", "下午好，别太累"
    ),
    DayPeriod.EVENING to listOf(
        "晚上好", "傍晚好", "晚上好呀", "入夜了，晚上好",
        "晚上好，今天辛苦了", "晚上好，收个尾吧", "晚上好，放松一下"
    ),
    DayPeriod.NIGHT to listOf(
        "夜深了", "晚上好", "还没睡呀", "夜猫子时间",
        "这么晚还在，辛苦了", "深夜好，注意休息", "该收工了，晚安"
    )
)

/** 子问候 20 条。 */
private val SUB_GREETINGS = listOf(
    "今天吃了没",
    "昨晚睡得好吗",
    "该睡了，别熬太晚",
    "记得多喝水",
    "今天也要开心一点",
    "累了就歇会儿",
    "摸摸鱼也没关系",
    "今天想聊点什么",
    "有没有什么烦心事",
    "要不要一起理一下计划",
    "别忘了站起来活动下",
    "眼睛也歇一歇吧",
    "今天有什么小收获",
    "慢慢来，不着急",
    "别对自己太苛刻",
    "给自己留点空档",
    "天气怎么样",
    "心情还顺利吗",
    "想听点轻松的，还是想认真干活",
    "有事随时喊我"
)

/** 建议 20 条（轮换展示，不必一次全列）。 */
private val TIPS = listOf(
    "可以把问题丢给我，我帮你拆解",
    "试试让我写一段文案或说明",
    "让我帮你总结一篇长文",
    "丢一段代码给我，一起看看",
    "要不要理一下今天的待办",
    "让我用通俗的话解释一个概念",
    "可以让我帮你列个对比表",
    "需要起标题、起名字，交给我",
    "想练口语，我可以陪你对话",
    "让我帮你写一封邮件",
    "有看不懂的报错，发给我",
    "需要翻译，中英日韩都行",
    "让我帮你规划一次出行",
    "要不要做个读书笔记",
    "把零散想法说给我，我帮你理清",
    "需要一段正则或脚本，可以试试",
    "让我帮你优化一段文字",
    "想不出吃什么，我来出主意",
    "要我扮演个角色也可以",
    "从「聊天」进去，随时开聊"
)

/** "11月16日 · 周日"；用固定映射避免不同 ROM 的星期缩写不一致。 */
private fun dateLabel(date: LocalDate): String {
    val weekday = when (date.dayOfWeek.value) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        else -> "周日"
    }
    return "${date.monthValue}月${date.dayOfMonth}日 · $weekday"
}
