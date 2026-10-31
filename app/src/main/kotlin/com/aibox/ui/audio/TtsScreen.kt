package com.aibox.ui.audio

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aibox.AIBoxApp
import com.aibox.infra.plugin.FieldType
import com.aibox.infra.plugin.SettingField
import com.aibox.ui.components.unified.UnifiedDropdown
import com.aibox.ui.components.unified.UnifiedSection
import com.aibox.ui.components.unified.UnifiedSlider
import com.aibox.ui.theme.Colors
import com.aibox.ui.theme.DesignTokens
import com.aibox.ui.theme.Type

/**
 * 语音合成页（D2）。
 *
 * 布局：TopAppBar + Section "文本" + Section "语言" + Section "语音参数"（语速 / 音调）
 * + Section "合成与播放"（播放 / 停止按钮）。
 *
 * 无需特殊权限（系统 TTS 不需要 RECORD_AUDIO）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsScreen(
    onBack: () -> Unit,
    vm: TtsViewModel = viewModel(
        factory = TtsViewModelFactory(AIBoxApp.instance.container.audioProviderRegistry)
    )
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(ui.error) {
        ui.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("语音合成", style = Type.titleLarge) }
            )
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(vertical = DesignTokens.spacing16),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)
        ) {
            item {
                UnifiedSection(title = "文本", showTopDivider = false) {
                    Column(modifier = Modifier.padding(horizontal = DesignTokens.spacing16)) {
                        Text(text = "待合成文本", style = Type.titleSmall, color = Colors.onSurface)
                        Spacer(Modifier.height(DesignTokens.spacing4))
                        OutlinedTextField(
                            value = ui.text,
                            onValueChange = vm::setText,
                            placeholder = { Text("请输入要朗读的文本…") },
                            minLines = 4,
                            maxLines = 8,
                            textStyle = Type.bodyLarge,
                            shape = DesignTokens.shapeMedium,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "字符数: ${ui.text.length}",
                            style = Type.labelSmall,
                            color = Colors.onSurfaceVariant,
                            modifier = Modifier.padding(top = DesignTokens.spacing4)
                        )
                    }
                }
            }
            item {
                UnifiedSection(title = "语言") {
                    UnifiedDropdown(
                        field = SettingField(
                            key = "tts_language",
                            type = FieldType.DROPDOWN,
                            title = "合成语言",
                            description = if (ui.providerId.isEmpty()) "无可用 provider"
                                else "Provider: ${ui.providerId}",
                            defaultValue = ui.language,
                            options = LANGUAGE_OPTIONS
                        ),
                        value = ui.language,
                        onValueChange = vm::setLanguage
                    )
                }
            }
            item {
                UnifiedSection(title = "语音参数") {
                    UnifiedSlider(
                        field = SettingField(
                            key = "tts_rate", type = FieldType.SLIDER,
                            title = "语速", description = "1.0 = 正常语速",
                            defaultValue = 1.0f, min = 0.5f, max = 2.0f, step = 0.1f
                        ),
                        value = ui.speechRate,
                        onValueChange = vm::setSpeechRate
                    )
                    UnifiedSlider(
                        field = SettingField(
                            key = "tts_pitch", type = FieldType.SLIDER,
                            title = "音调", description = "1.0 = 正常音调",
                            defaultValue = 1.0f, min = 0.5f, max = 2.0f, step = 0.1f
                        ),
                        value = ui.pitch,
                        onValueChange = vm::setPitch
                    )
                }
            }
            item {
                UnifiedSection(title = "合成与播放") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = DesignTokens.spacing16),
                        horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing12)
                    ) {
                        Button(
                            onClick = vm::play,
                            enabled = ui.available && !ui.isPlaying && ui.text.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(DesignTokens.spacing8))
                            Text(if (ui.isPlaying) "播放中…" else "合成并播放")
                        }
                        OutlinedButton(
                            onClick = vm::stop,
                            enabled = ui.isPlaying,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(DesignTokens.spacing8))
                            Text("停止")
                        }
                    }
                    if (!ui.available) {
                        Text(
                            text = "当前设备不支持语音合成",
                            style = Type.bodySmall,
                            color = Colors.error,
                            modifier = Modifier.padding(
                                horizontal = DesignTokens.spacing16,
                                vertical = DesignTokens.spacing8
                            )
                        )
                    }
                }
            }
        }
    }
}
