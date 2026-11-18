package com.aibox.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.aibox.AIBoxApp
import com.aibox.data.repo.CloudModelProfile
import com.aibox.domain.cloud.CloudConfig
import com.aibox.domain.cloud.CloudPresets
import com.aibox.domain.cloud.CloudProbe
import com.aibox.domain.cloud.CloudProbeFailure
import com.aibox.domain.cloud.CloudProbeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 添加 / 编辑云端模型。
 *
 * 每个配置都会作为「一个模型」出现在模型列表里；选中后才写入当前 CloudConfig。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudModelEditScreen(
    profileId: Long = 0L,
    onBack: () -> Unit
) {
    val repo = AIBoxApp.instance.container.cloudModelRepository
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(profileId <= 0L) }
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetchingModels by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var probeResult by remember { mutableStateOf<CloudProbeResult?>(null) }
    var probeMessage by remember { mutableStateOf<String?>(null) }
    var vision by remember { mutableStateOf(false) }
    var audio by remember { mutableStateOf(false) }
    var builtinWeb by remember { mutableStateOf(false) }
    var builtinUrl by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(profileId) {
        if (profileId > 0L) {
            repo.get(profileId)?.let { p ->
                name = p.name
                baseUrl = p.baseUrl
                model = p.model
                apiKey = p.apiKey
                vision = p.supportsVision
                audio = p.supportsAudio
                builtinWeb = p.builtinWebSearch
                builtinUrl = p.builtinUrlReader
            }
            loaded = true
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
                title = { Text(if (profileId > 0L) "编辑云端模型" else "添加云端模型") }
            )
        }
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "常用服务",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CloudPresets.all.forEach { preset ->
                    FilterChip(
                        selected = baseUrl == preset.baseUrl && model == preset.model,
                        onClick = {
                            baseUrl = preset.baseUrl
                            model = preset.model
                            if (name.isBlank()) name = preset.label
                        },
                        label = { Text(preset.label) }
                    )
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("显示名称") },
                placeholder = { Text("例：DeepSeek 主力") },
                singleLine = true
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Base URL") },
                placeholder = { Text("https://api.deepseek.com/v1") },
                singleLine = true
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it; probeResult = null; probeMessage = null },
                    modifier = Modifier.weight(1f),
                    label = { Text("模型 ID") },
                    placeholder = { Text("deepseek-chat") },
                    singleLine = true
                )
                OutlinedButton(
                    onClick = {
                        val base = baseUrl.trim().trimEnd('/')
                        if (base.isBlank()) {
                            probeMessage = "请先填写 Base URL"
                            return@OutlinedButton
                        }
                        fetchingModels = true
                        probeMessage = null
                        scope.launch {
                            try {
                                models = CloudProbe.listModels(
                                    CloudConfig(baseUrl = base, apiKey = apiKey.trim(), model = model.trim())
                                )
                                if (models.isEmpty()) probeMessage = "接口没有返回模型列表，可手动填写模型 ID"
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (t: Throwable) {
                                probeMessage = (t as? CloudProbeFailure)?.error?.display
                                    ?: (t.message ?: "获取模型列表失败")
                            } finally {
                                fetchingModels = false
                            }
                        }
                    },
                    enabled = !fetchingModels
                ) {
                    if (fetchingModels) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("获取列表")
                    }
                }
            }
            if (models.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(models) { id ->
                        FilterChip(
                            selected = model == id,
                            onClick = { model = id; probeResult = null },
                            label = { Text(id) }
                        )
                    }
                }
            }
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; probeResult = null; probeMessage = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API Key") },
                placeholder = { Text("sk-…（本地 Ollama 可留空）") },
                singleLine = true,
                visualTransformation = if (keyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            if (keyVisible) Icons.Outlined.VisibilityOff
                            else Icons.Outlined.Visibility,
                            contentDescription = if (keyVisible) "隐藏 API Key" else "显示 API Key"
                        )
                    }
                }
            )

            OutlinedButton(
                onClick = {
                    val base = baseUrl.trim().trimEnd('/')
                    if (base.isBlank() || model.isBlank()) {
                        probeMessage = "Base URL 与模型 ID 不能为空"
                        return@OutlinedButton
                    }
                    testing = true
                    probeResult = null
                    probeMessage = null
                    scope.launch {
                        probeResult = try {
                            CloudProbe.probe(
                                CloudConfig(
                                    baseUrl = base,
                                    apiKey = apiKey.trim(),
                                    model = model.trim()
                                )
                            )
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            CloudProbeResult(
                                error = com.aibox.domain.cloud.CloudProbeError(
                                    com.aibox.domain.cloud.CloudProbeErrorKind.OTHER,
                                    null,
                                    (t.message ?: t.javaClass.simpleName).take(300)
                                )
                            )
                        }
                        testing = false
                    }
                },
                enabled = !testing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text("测试中…")
                } else {
                    Text("测试连接")
                }
            }
            probeResult?.let { r ->
                Text(
                    r.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (r.success) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }
            probeMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Text(
                "能力与内置工具",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
            CapabilitySwitch("支持图片输入（多模态）", vision) { vision = it }
            CapabilitySwitch("支持音频输入", audio) { audio = it }
            CapabilitySwitch("供应商自带联网搜索", builtinWeb) { builtinWeb = it }
            CapabilitySwitch("供应商自带 URL 阅读", builtinUrl) { builtinUrl = it }
            Text(
                "后两项表示该服务自己会联网；开启后仍保留本地工具开关，但可减少重复注入。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (name.isBlank() || baseUrl.isBlank() || model.isBlank()) {
                        error = "名称、Base URL、模型 ID 都不能为空"
                        return@Button
                    }
                    scope.launch {
                        runCatching {
                            repo.upsert(
                                CloudModelProfile(
                                    id = profileId,
                                    name = name.trim(),
                                    baseUrl = baseUrl.trim(),
                                    apiKey = apiKey.trim(),
                                    model = model.trim(),
                                    supportsVision = vision,
                                    supportsAudio = audio,
                                    builtinWebSearch = builtinWeb,
                                    builtinUrlReader = builtinUrl
                                )
                            )
                        }.onSuccess { onBack() }
                            .onFailure { error = "保存失败：${it.message}" }
                    }
                },
                enabled = loaded,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) { Text("保存") }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CapabilitySwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
