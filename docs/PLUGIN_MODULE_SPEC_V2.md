# 插件 / 模块规范 v2（含自定义模型导入与 SD 生图模块）

> 记录时间：2026-11-19
> 状态：设计稿，不是已实现接口
> 现有实现参考：mobile-ai-box `docs/PLUGIN_DEVELOPMENT.md`
> 目的：为推理后端、聊天模块、功能模块（含 SD 生图）、工具插件、模型导入器建立统一契约。

---

## 1. 现状

代码仓库已有：

- 三类插件：推理框架后端 / 聊天模块 / 工具插件（工具 AIDL 未定义）
- AIDL：`IPluginInterface`、`IPluginDescriptor`、`IStreamCallback`、`IPluginLogCallback`
- `module.json`：聊天模块的模型与推荐配置
- `BackendDescriptor`：能力 + 设置 schema
- `ParcelFileDescriptor` 传大模型，避免 Binder 1MB 限制
- 插件独立 APK、独立签名、PackageManager 发现

缺口：

- 没有统一的“功能模块”类型（SD 生图属于这一类）
- 没有正式的模型导入器契约（自定义格式、多文件 bundle）
- 没有统一的权限/能力申请模型
- 没有模块间访问宿主能力的 API 清单
- 没有 SD 模块的 generate / progress / cancel / 输出契约
- 没有资源预算与降级规范

---

## 2. 模块分类

| 类型 | 职责 | 是否带模型 | 是否带 native | 典型 |
|---|---|---|---|---|
| inference-backend | 提供推理框架（llama.cpp / MNN / LiteRT） | 否 | 是 | MNN NPU 后端 |
| chat-module | 装好即用的聊天模型包 | 是 | 可选 | Easy Chat |
| feature-module | 提供完整功能（SD 生图、ASR、TTS、翻译） | 可选 | 是 | SD 生图模块 |
| tool-plugin | 提供工具调用能力 | 否 | 可选 | 天气、搜索、文件 |
| importer-plugin | 识别/导入特殊模型格式 | 否 | 可选 | TokForge NPU 包导入 |

一个 APK 可以声明多个类型，但必须分别声明能力和权限。

---

## 3. 设计原则

1. **宿主中介**：模块不直接访问宿主数据库、任意文件、任意 ContentResolver
2. **能力申请**：按 capability 授权，不按包名给全量权限
3. **进程隔离**：模块独立进程，崩溃不拖垮宿主
4. **契约版本化**：模块声明 hostApiLevel / minHost / maxHost
5. **大文件零拷贝**：ParcelFileDescriptor / 共享内存，不用 `byte[]`
6. **失败可回退**：模块缺失、崩溃、超时、版本不匹配时宿主能降级
7. **用户可见**：安装、授权、资源占用、输出位置都要明确
8. **不做动态代码加载**：代码只能来自已安装 APK

---

## 4. 包与 manifest

### 4.1 工程形态

- 独立 Gradle 工程、独立 applicationId、独立签名
- 通过 Intent Action 被宿主发现
- AIDL 与宿主保持字节级一致，或引用 `aibox-plugin-api` AAR

### 4.2 Intent Action

```
com.aibox.action.BACKEND_PLUGIN
com.aibox.action.CHAT_MODULE
com.aibox.action.FEATURE_MODULE
com.aibox.action.TOOL_PLUGIN
com.aibox.action.IMPORTER_PLUGIN
```

### 4.3 module.json（建议 v2）

```json
{
  "schemaVersion": 2,
  "id": "com.example.sd",
  "name": "SD Image Gen",
  "displayName": "本地生图",
  "version": "1.0.0",
  "type": ["feature-module", "tool-plugin"],
  "hostApiLevel": 2,
  "minHostVersion": "261118-4",
  "maxHostVersion": "",
  "entry": {
    "service": "com.example.sd.SdService",
    "process": ":sd"
  },
  "capabilities": [
    "image.generate",
    "image.img2img",
    "image.inpaint",
    "image.upscale",
    "tool.provide"
  ],
  "permissions": [
    "model.read",
    "model.write",
    "image.write",
    "gpu.use",
    "npu.use",
    "notification.post"
  ],
  "modelFormats": [
    {
      "id": "sd-bundle",
      "kind": "bundle",
      "extensions": [".safetensors", ".gguf", ".onnx", ".mnn"],
      "roles": ["diffusion", "vae", "text_encoder", "lora", "controlnet"],
      "importer": "com.example.sd.SdImporter"
    }
  ],
  "tools": [
    {
      "id": "image.generate",
      "description": "根据提示词生成图片",
      "inputSchema": {},
      "risk": "write",
      "dataClass": "personal",
      "timeoutMs": 300000
    }
  ],
  "resources": {
    "maxRamMb": 6144,
    "maxVramMb": 4096,
    "maxThreads": 4,
    "maxConcurrentJobs": 1,
    "requiresChargingForLongJobs": true
  },
  "ui": {
    "settingsSchema": true,
    "customScreen": false
  },
  "author": {},
  "license": "MIT"
}
```

### 4.4 校验规则

- `id` 全局唯一，反向域名
- `version` 语义化版本
- `hostApiLevel` 必须 ≤ 宿主支持
- `minHostVersion` / `maxHostVersion` 兼容检查
- 未声明的 capability / permission 一律拒绝
- manifest 必须签名校验通过

---

## 5. 生命周期契约

```
discover → verify → install → enable → bind → init → ready
  → busy → idle → suspend → unload → disable → uninstall
```

要求：

- `init` 必须可超时（建议 10s），超时宿主解绑
- `suspend` 释放可释放资源，但保留模型缓存策略由模块声明
- `unload` 必须释放 native / GPU / NPU 资源
- 宿主 `onTrimMemory` 要转发给模块
- 模块崩溃后宿主标记不可用，本次会话不反复重启
- 卸载时宿主清理注册表、缓存、临时文件

---

## 6. 推理后端契约

沿用现有 `IPluginInterface`，但补齐 v2 语义：

- `describeCapabilities()`：后端类型、量化、KV、流式、多模态、工具调用
- `getSettingsSchema()` + `parseSettings()`
- `loadModel(LoadModelRequest)`：路径、量化、上下文、线程、后端偏好、frameworkExtras
- `infer(InferRequest, IStreamCallback)`：流式 token、错误、结束
- `cancel(requestId)`：必须可中断
- `unload()`：释放资源
- `getMemoryEstimate()`：权重 / KV / compute / 峰值
- `getLastError()`：结构化错误码

大文件：

- 宿主通过 `ParcelFileDescriptor` 传模型
- 模块不得复制整份模型到私有目录，除非导入器明确需要
- 多文件模型用 `openModelFileDescriptor(role)` 按角色取

---

## 7. 模型导入器契约

### 7.1 目标

支持：

- 单文件：gguf / safetensors / onnx / mnn / tflite / litertlm
- 多文件 bundle：分片、VAE、text encoder、LoRA、ControlNet
- 压缩包 / 文件夹
- 特殊包：TokForge NPU 包、未来的其他 runtime 包

### 7.2 导入流程

```
用户选择 URI / 文件夹 / 压缩包
  → 宿主做安全检查（zip-slip / zip bomb / 大小 / 符号链接）
  → 宿主复制到 staging（模块不可直接读原路径）
  → 模块 Importer.inspect(staging)
  → 返回 ImportPlan（角色、文件、大小、hash、依赖、风险）
  → 用户确认
  → 模块 Importer.import(plan) 或宿主执行
  → 宿主注册 ModelRecord
  → 清理 staging
```

### 7.3 Importer 接口

```
canHandle(uri): Boolean
inspect(stagingDir): ImportPlan
import(plan): ImportResult
validate(modelRecord): ValidationResult
cleanup(modelRecord)
```

`ImportPlan`：

```
components:
  - role: diffusion | vae | text_encoder | lora | controlnet | tokenizer | config
    path
    sizeBytes
    sha256
    format
    quantization
required: Boolean
warnings: List<String>
estimatedRamMb
license
```

### 7.4 宿主职责

- 文件选择、权限、URI 持久化
- 解压与安全检查
- 磁盘空间检查
- 复制/移动、hash 去重
- 注册表、UI、卸载清理
- 绝不把任意路径直接交给模块

### 7.5 模块职责

- magic / 格式嗅探
- 角色识别（哪个文件是 VAE、哪个是 LoRA）
- 完整性校验
- 兼容性判断（架构、量化、版本）
- 推荐后端与配置
- 转换提示（不能静默转换）

---

## 8. 功能模块契约（以 SD 生图为例）

### 8.1 能力

```
image.generate
image.img2img
image.inpaint
image.upscale
image.controlnet
image.lora
```

### 8.2 请求

```json
{
  "jobId": "uuid",
  "mode": "txt2img",
  "prompt": "...",
  "negativePrompt": "...",
  "width": 512,
  "height": 512,
  "steps": 20,
  "cfgScale": 7.0,
  "seed": -1,
  "sampler": "euler_a",
  "batchSize": 1,
  "modelId": "sd-base",
  "vaeId": "vae-1",
  "lora": [{"id": "lora-1", "weight": 0.7}],
  "controlnet": [],
  "inputImage": "content://...",
  "mask": "content://...",
  "output": {"location": "host", "saveToVault": false}
}
```

### 8.3 回调

```
onQueued
onLoadingModel(progress)
onProgress(step, totalSteps, previewBitmap?)
onPreview(previewBitmap, stepInterval)
onResult(ImageResult)
onError(code, message, recoverable)
```

### 8.4 结果

```
imageId
uri
width / height
seed
modelId
durationMs
peakRamMb
metadata
```

### 8.5 资源与降级

- 同时只跑 1 个 job，其余排队
- 低内存时降低分辨率 / 步数 / batch，必须提示
- 过热暂停，降温继续
- 低电量建议充电或拒绝长任务
- 支持取消，取消后释放资源
- 预览图默认低频（每 N 步），可关闭

### 8.6 输出

- 宿主负责写入 MediaStore 或保险箱
- 模块不得写任意路径
- 缩略图、缓存、临时图由宿主管理
- 保险箱输出使用 scoped key 加密
- 导出时保留 metadata sidecar

---

## 9. 工具插件契约

```json
{
  "id": "image.generate",
  "description": "根据提示词生成图片",
  "inputSchema": {},
  "scopes": ["image.write"],
  "risk": "write",
  "dataClass": "personal",
  "timeoutMs": 300000,
  "maxResultBytes": 32768
}
```

规则：

- 模型只看到 schema，不直接访问模块 API
- 参数按 schema 校验，拒绝额外字段
- 结果大小上限，超出截断/摘要
- 模块结果视为不可信内容，防提示词注入
- 写操作 / 删除 / 联网每次确认
- 全量审计：工具、参数 hash、结果 hash、耗时、发起模型

---

## 10. 宿主能力 API（模块可申请）

| API | 用途 | 权限 |
|---|---|---|
| HostModelRegistry | 查询/注册模型、角色、hash | model.read / model.write |
| HostStorage | 模块私有目录、staging、缓存 | storage.private |
| HostImport | 选择文件、导入、校验 | model.import |
| HostImageStore | 保存图片、缩略图、保险箱 | image.write |
| HostCrypto | scoped key、加密导出 | vault.scope |
| HostTools | 注册/调用工具 | tool.provide / tool.call |
| HostNotifications | 进度、完成、失败 | notification.post |
| HostTelemetry | 日志、指标、审计 | telemetry.write |
| HostDevice | 内存、温度、电量、后端能力 | device.read |

原则：

- 能力按需申请，用户可撤销
- 模块拿不到宿主 Master Key
- 保险箱解锁后才发放 scoped key
- 所有调用带 moduleId + jobId，便于审计

---

## 11. IPC 与数据传输

- AIDL + Binder 做控制通道
- 大文件用 `ParcelFileDescriptor`
- 大张量/图片用 shared memory / AHardwareBuffer（后续）
- 流式用 callback，不用 `Flow`
- 单次 Binder 数据 < 1MB
- 所有调用带超时
- 模块进程死亡，宿主收到 `DeathRecipient` 并回退

---

## 12. 存储布局

```
files/modules/<moduleId>/        模块私有
files/modules/<moduleId>/cache/  可清理
files/models/<modelId>/          宿主模型注册表
files/import-staging/<uuid>/     导入临时
files/outputs/images/            宿主图片库
vault/...                        保险箱（加密）
```

规则：

- 模块只能拿宿主分配的目录
- 卸载清理私有目录与缓存
- 模型按 hash 去重，多模块可共享只读
- 输出由宿主落盘，模块只拿 URI

---

## 13. 权限与作用域

```
model.read
model.write
model.import
storage.private
image.write
vault.scope
gpu.use
npu.use
notification.post
network
telemetry.write
device.read
```

- 安装时展示
- 首次使用时二次确认高风险权限
- 可在设置中撤销
- 撤销后模块下次调用返回 `PERMISSION_DENIED`
- `network` 默认拒绝，除非用户明确授权

---

## 14. 资源预算

模块必须报告：

- 预计权重内存
- KV / 中间张量
- GPU / NPU 显存或 dmabuf
- 峰值 RSS
- 单步耗时
- 热与电量敏感度

宿主负责：

- 加载前预算检查
- 并发限制
- 低内存/过热/低电量降级
- 任务队列与取消
- 失败回退

---

## 15. 安全要求

- APK 签名校验；可选开发者白名单
- manifest 严格校验，未知字段拒绝
- 解压防 zip-slip / zip bomb / 符号链接
- 路径必须规范化并限制在 staging 内
- 模型 hash 校验
- 不允许多余 Intent / 隐式广播
- 不允许动态 dex / 反射加载外部代码
- native 库只从 APK lib 目录加载
- 日志脱敏：不记录 prompt 原文、图片内容、密钥
- 保险箱数据只在解锁后通过 scoped key 访问

---

## 16. 版本与兼容

- `hostApiLevel`：整数，宿主递增
- `minHostVersion` / `maxHostVersion`
- `moduleVersion` 语义化
- 模型格式版本单独声明
- 不兼容时：禁用 + 明确提示，不静默失败
- 旧 API 至少保留一个大版本
- 插件矩阵文档维护兼容表

---

## 17. 错误与回退

统一错误码：

```
NOT_INSTALLED
DISABLED
VERSION_MISMATCH
PERMISSION_DENIED
MODEL_MISSING
MODEL_INCOMPATIBLE
OUT_OF_MEMORY
DEVICE_UNSUPPORTED
TIMEOUT
CANCELLED
CRASHED
INTERNAL
```

宿主行为：

- 可恢复错误：提示 + 重试
- 不可恢复：回退到内置后端/CPU
- 崩溃：标记本次会话不可用，避免循环重启
- 所有错误写入审计日志

---

## 18. UI 契约

- 模块通过 `settingsSchema` 提供设置项，宿主动态渲染
- 功能模块可以提供自己的页面，但默认在独立进程/独立 Activity
- SD 模块建议：
  - 宿主提供“生图”入口、参数页、进度页、结果页
  - 模块只提供能力与参数 schema
  - 高级参数折叠
  - 预览图可关闭
- 不在宿主主界面嵌入模块任意 UI
- 深色/大屏/横屏要跟随宿主主题

---

## 19. SD 模块落地建议

### 19.1 模型组件

```
diffusion model
VAE
text encoder
tokenizer
LoRA（可多个）
ControlNet（可选）
```

### 19.2 导入

- 支持单文件与 bundle
- 自动识别角色，识别失败让用户手动指定
- 显示缺失组件与预计内存
- 检查磁盘空间
- 记录 hash 与来源

### 19.3 运行

- 默认 512x512、20 步、batch 1
- 低内存自动降级并提示
- 进度 + 预览
- 可取消
- 输出到宿主图片库，可选保险箱

### 19.4 工具集成

- 注册 `image.generate` 工具
- 云端/本地模型可通过 Tool Gateway 调用
- 需要用户确认与审计
- 结果只返回 imageId / uri / 摘要，不返回 base64

### 19.5 安全

- 模型来源与 license 展示
- 不自动下载
- 不执行模型内脚本
- 输出 metadata 可剥离

---

## 20. 测试与示例

- 提供 `sample-feature-module`：一个最小 SD mock，返回纯色图
- 契约测试：
  - manifest 校验
  - 生命周期
  - 权限拒绝
  - 超时/崩溃/取消
  - 大文件传输
  - 内存不足降级
  - 输出落盘与保险箱
- 插件矩阵：模块 × 宿主版本 × 设备

---

## 21. 优先级

```
P1：manifest v2 + 权限模型 + 生命周期
P2：模型导入器契约 + 单文件/bundle
P3：SD 功能模块契约 + 输出到宿主
P4：工具网关接入
P5：保险箱 scoped key
P6：大屏/主题适配
P7：插件矩阵与契约测试
```

---

## 22. 结论

- 现有 `PLUGIN_DEVELOPMENT.md` 覆盖了推理后端和聊天模块，但不足以支撑 SD 这类功能模块
- 需要补：manifest v2、功能模块类型、导入器契约、权限/能力 API、资源预算、SD generate 契约
- 核心原则：宿主中介、能力授权、进程隔离、版本化、可回退
- SD 模块应作为“功能模块 + 工具插件”实现，输出和存储交给宿主
- 先定契约，再写实现，避免第二个模块出现时再返工
