package com.aibox.ui.audio

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aibox.AIBoxApp
import com.aibox.infra.plugin.FieldType
import com.aibox.infra.plugin.SettingField
import com.aibox.ui.components.unified.UnifiedCard
import com.aibox.ui.components.unified.UnifiedCardTone
import com.aibox.ui.components.unified.UnifiedDropdown
import com.aibox.ui.components.unified.UnifiedSection
import com.aibox.ui.theme.Colors
import com.aibox.ui.theme.DesignTokens
import com.aibox.ui.theme.Type

/**
 * 语音识别页（D1）。
 * 布局：TopAppBar + "语言" + "录音"（大圆形按钮）+ "识别结果"（复制 / 清空）。
 * 录音权限用 [ActivityResultContracts.RequestPermission] 运行时申请。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AsrScreen(
    onBack: () -> Unit,
    vm: AsrViewModel = viewModel(
        factory = AsrViewModelFactory(AIBoxApp.instance.container.audioProviderRegistry)
    )
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.start()
        else Toast.makeText(context, "需要录音权限才能进行语音识别", Toast.LENGTH_SHORT).show()
    }

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
                title = { Text("语音识别", style = Type.titleLarge) }
            )
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(vertical = DesignTokens.spacing16),
            verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing8)
        ) {
            item {
                UnifiedSection(title = "语言", showTopDivider = false) {
                    UnifiedDropdown(
                        field = SettingField(
                            key = "asr_language",
                            type = FieldType.DROPDOWN,
                            title = "识别语言",
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
                UnifiedSection(title = "录音") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = DesignTokens.spacing16),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(DesignTokens.spacing12)
                    ) {
                        FilledIconButton(
                            onClick = {
                                if (ui.isListening) {
                                    vm.stop()
                                } else if (ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                ) {
                                    vm.start()
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            modifier = Modifier.size(96.dp),
                            enabled = ui.available
                        ) {
                            Icon(
                                imageVector = if (ui.isListening) Icons.Outlined.Stop else Icons.Outlined.Mic,
                                contentDescription = if (ui.isListening) "停止" else "开始录音",
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Text(
                            text = when {
                                !ui.available -> "当前设备不支持语音识别"
                                ui.isListening -> "正在聆听…（点击停止）"
                                else -> "点击开始录音"
                            },
                            style = Type.bodyMedium,
                            color = Colors.onSurfaceVariant
                        )
                    }
                }
            }
            item {
                UnifiedSection(title = "识别结果") {
                    UnifiedCard(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = DesignTokens.spacing16),
                        tone = UnifiedCardTone.SURFACE_LOW
                    ) {
                        Text(
                            text = ui.finalText.ifBlank { "（暂无识别结果）" },
                            style = Type.bodyLarge,
                            color = if (ui.finalText.isBlank()) Colors.onSurfaceVariant else Colors.onSurface
                        )
                    }
                    Spacer(Modifier.height(DesignTokens.spacing8))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = DesignTokens.spacing16),
                        horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacing12)
                    ) {
                        OutlinedButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(ui.finalText))
                                Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                            },
                            enabled = ui.finalText.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(DesignTokens.spacing8))
                            Text("复制")
                        }
                        OutlinedButton(
                            onClick = vm::clearText, enabled = ui.finalText.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) { Text("清空") }
                    }
                }
            }
        }
    }
}
