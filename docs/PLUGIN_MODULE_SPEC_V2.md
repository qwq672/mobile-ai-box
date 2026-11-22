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


### 4.5 依赖声明与解析

> 现状：`PluginManager` / `PluginCompatibilityChecker` 目前只校验 `minAiboxVersion`、`targetSdk`、ABI、签名。
> 还没有模块之间的依赖图，也没有“后端缺失时功能模块自动禁用”的机制。v2 必须补上。

#### 4.5.1 依赖类型

| 类型 | 示例 | 缺失时 |
|---|---|---|
| 宿主版本 | `host.minVersion` / `hostApiLevel` | 禁用模块 |
| 模块依赖 | SD 模块依赖 MNN 后端模块 | 禁用，提示安装 |
| 能力依赖 | `image.generate`、`backend.mnn` | 找提供者，找不到禁用 |
| 模型依赖 | diffusion / VAE / text encoder | 提示导入，不自动下载 |
| 工具依赖 | `image.generate` 工具版本 | 禁用相关工具 |
| 设备依赖 | RAM、ABI、Android 版本、GPU/NPU | 降级或禁用 |
| 资源依赖 | LoRA、ControlNet、tokenizer | 可选则降级，必需则禁用 |
| 冲突声明 | 两个模块提供同一 backend id | 拒绝同时启用 |

#### 4.5.2 manifest 示例

```json
{
  "dependencies": {
    "host": {
      "apiLevel": 2,
      "minVersion": "261118-4",
      "maxVersion": ""
    },
    "modules": [
      {
        "id": "com.aibox.backend.mnn",
        "type": "inference-backend",
        "version": ">=1.2.0 <2.0.0",
        "required": true,
        "reason": "SD 模块通过 MNN 执行推理"
      },
      {
        "id": "com.aibox.backend.npu",
        "type": "inference-backend",
        "version": ">=0.9.0",
        "required": false,
        "reason": "可选 NPU 加速"
      }
    ],
    "capabilities": [
      {"id": "image.generate", "required": true},
      {"id": "npu.prefill", "required": false}
    ],
    "models": [
      {"role": "diffusion", "format": "safetensors", "required": true},
      {"role": "vae", "format": "safetensors", "required": true},
      {"role": "lora", "format": "safetensors", "required": false}
    ],
    "tools": [
      {"id": "image.generate", "version": ">=1.0.0", "required": true}
    ],
    "device": {
      "abis": ["arm64-v8a"],
      "minRamMb": 6144,
      "minAndroidSdk": 26,
      "gpu": "vulkan1.1",
      "npu": "optional"
    },
    "conflicts": [
      {"id": "com.example.sd.legacy", "reason": "同一 backend id"}
    ]
  }
}
```

#### 4.5.3 解析规则

1. **先解析，再 bind**：依赖不满足时不要启动 Service
2. **拓扑排序**：被依赖模块先 enable，宿主先 ready
3. **循环依赖直接拒绝**，并给出链路
4. **同一 moduleId 只允许一个激活版本**
5. **required 缺失 → 模块禁用**，错误里带可操作建议
6. **optional 缺失 → 降级**，功能开关置灰并说明原因
7. **不自动下载/安装依赖**，必须用户确认
8. **卸载依赖 → 依赖方 suspend**，重新安装后可恢复
9. **能力可被宿主内置实现满足**，优先宿主，其次模块
10. **冲突模块不能同时启用**，UI 明确提示

#### 4.5.4 版本约束

- 使用 semver 范围：`>=1.2.0 <2.0.0`、`^1.2.0`、`~1.2.0`、精确版本
- 兼容现有 `v261018-1` 形式：解析为 `261018-1` 再比较
- 宿主版本与模块版本分开比较
- 版本解析失败视为不兼容，并记录原始字符串
- 现有 `aibox.minVersion` meta-data 保留为“快速过滤”，module.json 为权威；两者冲突取更严格

#### 4.5.5 依赖状态与 UI

```
MISSING       未安装
INCOMPATIBLE  版本不满足
DISABLED      用户未启用
READY         可用
DEGRADED      可选依赖缺失
CONFLICT      与其他模块冲突
```

- 模块详情页展示依赖树
- 缺失项提供“去安装/去导入/去启用”
- 不把技术错误直接丢给用户，给可操作文案
- 日志记录解析结果、缺失链路、最终启用顺序

#### 4.5.6 安全

- 依赖的 moduleId 必须与包名/签名绑定，不能只信 manifest 字符串
- 第三方模块作为依赖时，首次启用需用户确认
- 防止“依赖劫持”：同名 moduleId 但不同签名 → 拒绝
- 依赖解析结果缓存，但安装/卸载/升级后必须失效


---


### 4.6 必选前置与可选前置的交互

#### 4.6.1 定义

```
必选前置（required）
  缺失 → 模块不能启用 / 不能启动 / 不能执行任务

可选前置（optional）
  缺失 → 模块照常启动；只有用到相关能力时才提示
```

两者都必须显式声明，不能靠运行时猜。

#### 4.6.2 manifest 表达

```json
{
  "dependencies": {
    "modules": [
      {
        "id": "com.aibox.backend.mnn",
        "version": ">=1.2.0 <2.0.0",
        "required": true,
        "promptAt": "install",
        "reason": "SD 模块依赖 MNN 执行推理"
      },
      {
        "id": "com.aibox.backend.npu",
        "version": ">=0.9.0",
        "required": false,
        "promptAt": "firstUse",
        "triggerCapabilities": ["npu.prefill"],
        "fallback": "cpu",
        "promptText": "安装 NPU 后端可显著加快预填充",
        "rememberDismiss": true
      },
      {
        "id": "com.aibox.module.lora.manager",
        "version": "^1.0.0",
        "required": false,
        "promptAt": "startup",
        "fallback": "disable_feature",
        "rememberDismiss": true
      }
    ]
  }
}
```

字段：

| 字段 | 说明 |
|---|---|
| `required` | true=必选，false=可选 |
| `promptAt` | `install` / `firstUse` / `startup` / `never` |
| `triggerCapabilities` | 可选依赖在哪些能力被调用时提示 |
| `fallback` | `cpu` / `disable_feature` / `none` |
| `promptText` | 用户可读的提示文案 |
| `rememberDismiss` | 用户选择“不再提醒”后是否记住 |

#### 4.6.3 必选前置

规则：

1. **安装/启用前检查**：缺失则进入 `BLOCKED_MISSING_REQUIRED`
2. **强制安装 = 阻止继续，不是静默安装**
   - 宿主展示依赖列表、大小、权限、来源
   - 用户确认后逐个安装，或一键安装全部
   - 用户取消 → 整个安装/启用取消
3. **不允许跳过**
4. **每次启动/每次任务前复查**，防止运行中被卸载
5. **运行中被卸载 → 立即 suspend**
   - 取消进行中的任务
   - 保存状态
   - 通知用户
   - 重新安装后恢复
6. **同 APK 内置的前置**（例如自带 .so）视为 bundled，不提示安装，但版本锁定
7. **外部 APK 前置必须用户确认**，不能后台静默装
8. 必选前置不能声明 `fallback`，缺失就是不可用

状态：

```
BLOCKED_MISSING_REQUIRED
BLOCKED_VERSION_MISMATCH
BLOCKED_USER_DECLINED
READY
```

#### 4.6.4 可选前置

规则：

1. **不阻塞安装、启用、启动**
2. **懒提示**：模块调用相关能力时，宿主 API 返回 `OPTIONAL_DEPENDENCY_MISSING`
   - 宿主弹窗：安装 / 启用 / 暂不
   - 弹窗说明：用途、大小、权限、替代方案
   - 模块不得崩溃，必须走 fallback
3. **启动提醒**：模块可在 init 或功能页调用 `host.dependencies.suggest(id)`
   - 由宿主统一渲染，不让模块自己弹窗
   - 频率限制：每个版本/会话最多一次
   - 用户可“不再提醒”
4. **用户控制**：每个可选依赖可设置
   - 使用时提醒
   - 启动时提醒
   - 不再提醒
5. **必须有 fallback**
   - `cpu`：降级到 CPU
   - `disable_feature`：相关入口置灰并说明
   - `none`：不允许，可选依赖必须能降级
6. **状态变化通知**：安装/卸载/启用/禁用后调用 `onDependencyChanged`
7. **不反复打扰**：用户拒绝后进入冷却期，不能每次点击都弹
8. **可选依赖不能影响基础功能**，否则应改为必选

状态：

```
AVAILABLE
MISSING
DISABLED
INCOMPATIBLE
DISMISSED
```

#### 4.6.5 宿主 API

```
dependencies.check(id): DependencyState
dependencies.require(id): Result        // 必选，缺失返回错误
dependencies.requestOptional(id): Result // 可选，缺失时触发宿主 UI
dependencies.suggest(id)                // 启动提醒，宿主决定是否展示
dependencies.observe(): Flow<DependencyEvent>
```

模块不得自己弹系统安装器或跳转应用商店，统一由宿主处理。

#### 4.6.6 错误码

```
DEPENDENCY_REQUIRED_MISSING
DEPENDENCY_REQUIRED_VERSION_MISMATCH
DEPENDENCY_OPTIONAL_MISSING
DEPENDENCY_USER_DECLINED
DEPENDENCY_INSTALL_FAILED
DEPENDENCY_UNINSTALLED_RUNTIME
DEPENDENCY_CONFLICT
```

#### 4.6.7 SD 模块示例

必选：

- MNN 后端（或内置 SD runtime）
- diffusion 模型
- VAE
- text encoder

可选：

- NPU 后端
- LoRA 管理器
- ControlNet
- 放大模型

行为：

- 没装 MNN → SD 模块不能启用
- 没装 NPU → 正常 CPU 生图；点“NPU 加速”时才提示
- 没装 LoRA 管理器 → LoRA 入口置灰；用户点 LoRA 时提示安装
- 运行中卸载 MNN → 当前 job 取消，模块 suspend

#### 4.6.8 测试矩阵

- 必选缺失：安装被阻止，错误可操作
- 必选版本不匹配：阻止并提示升级
- 必选运行中卸载：任务取消 + suspend
- 可选懒提示：首次调用弹一次
- 可选启动提醒：每版本最多一次
- 用户拒绝：冷却期内不再弹
- 用户“不再提醒”：持久化
- 可选依赖安装后：功能恢复，无需重启
- 可选依赖卸载后：功能降级，不崩溃
- 冲突模块：不能同时启用


---


### 4.7 原生库共享与 ABC 依赖

#### 4.7.1 先明确 Android 的边界

- 独立 APK 默认是不同 UID、不同进程
- 一个进程不能 dlopen 另一个 APK 私有目录里的 .so
- 即使宿主把 C 的 .so 解压出来，也只是复制到自己的目录，不是跨进程共享同一份映射
- 所以：**跨 APK 能共享的是能力（Binder/服务），不是 .so 代码段**

因此必须区分两种运行模型：

```
Mode H：宿主进程内加载（first-party / 动态模块）
  可以按 provider + 拓扑序 + RTLD_GLOBAL 共享同一份 .so

Mode S：独立 APK / 独立进程（third-party 插件）
  不能共享 .so；用 Binder 服务组合，或各自带一份副本
```

#### 4.7.2 Mode H：宿主进程内

适用：

- 模块由宿主内置或作为 dynamic feature
- 所有模块代码在宿主进程/同一 UID
- ModuleLoader 负责 native 加载

ABC 场景：

```
A 依赖宿主 libhost.so
B 依赖宿主 libhost.so + C 的 libC.so
C 只依赖自己的 libC.so
```

manifest：

```json
// A
"depends_on_libs": [
  {"soname": "libhost.so", "provider": "host", "shared": true, "required": true}
]

// B
"depends_on_libs": [
  {"soname": "libhost.so", "provider": "host", "shared": true, "required": true},
  {"soname": "libC.so", "provider": "module.c", "shared": true, "required": true}
]

// C
"native_libs_bundled": [
  {"soname": "libC.so", "file_name": "libC.so", "abi": "arm64-v8a", "sha256": "..."}
],
"depends_on_libs": [
  {"soname": "libC.so", "provider": "module.c", "shared": true, "required": true}
]
```

加载算法：

1. 扫描所有模块，建 provider 索引：
   - `soname -> provider (host / moduleId)`
   - 同一 soname 多个 provider → 冲突，拒绝加载
2. 校验每个 `depends_on_libs.provider`：
   - `host`：文件必须在宿主 `nativeLibraryDir` 或宿主声明的 host libs 中
   - `module.c`：C 的 manifest 必须声明 `native_libs_bundled` 包含该 soname
3. 按拓扑序加载：
   - 先加载宿主共享库（`libhost.so`）
   - 再加载 C 的 `libC.so`
   - 最后加载 A / B 自己的库
4. 路径解析：
   - host：`context.applicationInfo.nativeLibraryDir/<file_name>`
   - module：从 provider APK 的 `lib/<abi>/<file_name>` 解压到宿主私有目录
     `files/modules/native/<providerModuleId>/<abi>/<file_name>`
   - 解压后校验 sha256，原子替换，禁止符号链接/zip-slip
5. 加载模式：
   - `shared=true`：`RTLD_NOW | RTLD_GLOBAL`，让后续库能解析符号
   - `shared=false`：`RTLD_NOW | RTLD_LOCAL`
6. 引用计数：
   - owner = 请求加载的 moduleId
   - B 加载 libC 时，C 也是 owner；C 先卸载时引用计数不归零
   - 最后所有 owner 卸载才 dlclose
7. 卸载顺序：依赖者先卸，被依赖者后卸（拓扑逆序）

关键细节：

- `soname` 与文件名可能不同，manifest 必须提供 `file_name`
- B 的 `DT_NEEDED` 必须与 `soname` 一致；构建期用 `readelf -d` 校验
- 如果 B 的 .so 直接 `DT_NEEDED libC.so`，必须先 `dlopen` C 并 `RTLD_GLOBAL`，否则链接器找不到
- 如果 C 的 .so 还依赖别的库，要先递归加载 C 的依赖
- JNI 库注意：raw `dlopen` 不一定触发 `JNI_OnLoad`；JNI 入口库建议走 `System.load`/`Runtime.load0`，共享依赖库可走 `dlopen`
- Android 10+ 从可写目录 dlopen 有限制，解压目录要私有、不可写、校验 hash

失败处理：

- provider 缺失 → 模块不可加载
- provider 版本不满足 → 模块不可加载
- soname 冲突 → 拒绝同时加载
- ABI 不匹配 → 拒绝
- hash 不匹配 → 拒绝并清理
- `RTLD_GLOBAL` 冲突 → 要求 manifest 修正

#### 4.7.3 Mode S：独立 APK / 独立进程

ABC 场景下：

- A 不能直接拿到宿主的 `libhost.so`
- B 不能 dlopen C 的 `libC.so`
- C 只能在自己的进程加载自己的库

可选方案：

1. **服务组合（推荐）**
   - 宿主暴露 `IHostNativeService`
   - C 暴露 `ICoreNativeService`
   - A/B 通过 Binder 调用，不链接对方的 .so
   - 大数据用 shared memory / AHardwareBuffer / ParcelFileDescriptor
2. **各自带副本**
   - A 自带 host lib 的副本（注意 license/体积）
   - B 自带 C 的副本（内存/存储翻倍）
   - 只适合小库或临时方案
3. **同 APK / dynamic feature**
   - 把 A/B/C 变成宿主同一 UID 的模块
   - 回到 Mode H，才能真正共享 .so
4. **共享进程**
   - `sharedUserId` 已废弃且不安全，不推荐

结论：

- 第三方插件不要承诺“共享 .so”
- 应该承诺“共享能力接口”
- 真要共享 native 运行时，就把模块收进宿主或使用 first-party 动态模块

#### 4.7.4 规范字段补充

`native_libs_bundled` 增加：

```json
{
  "soname": "libC.so",
  "file_name": "libC.so",
  "abi": "arm64-v8a",
  "size_bytes": 123456,
  "sha256": "..."
}
```

`depends_on_libs` 增加：

```json
{
  "soname": "libC.so",
  "file_name": "libC.so",
  "provider": "module.c",
  "shared": true,
  "required": true,
  "min_version": "1.2.0"
}
```

规则：

- `provider` 必填：`host` 或 moduleId
- `file_name` 默认等于 soname，但允许不同
- `required=true` 缺失 → 模块不可加载
- `shared=true` 的库必须唯一 provider
- `sha256` 用于解压校验
- 构建期必须校验 `readelf -d` 的 SONAME 与 manifest 一致

#### 4.7.5 测试矩阵

- A 只依赖 host：通过
- B 依赖 host + C：C 先加载，B 后加载
- C 先卸载、B 仍持有：不 dlclose
- B 先卸载、C 后卸载：最后 dlclose
- provider 缺失：拒绝
- provider 版本不满足：拒绝
- 两个模块提供同 soname：冲突
- ABI 不匹配：拒绝
- hash 不匹配：拒绝并清理
- 解压目录不可写/被篡改：拒绝
- JNI 入口库：JNI_OnLoad 正常
- 独立进程插件：确认不会尝试跨 APK dlopen


---


### 4.8 模块载体选择：APK / Dynamic Feature / AAR / .aibox / 数据包

#### 4.8.1 载体类型

| 载体 | 是否独立安装 | 进程/UID | 适用 |
|---|---|---|---|
| 独立 APK | 是 | 独立 | 第三方插件、独立签名/更新/隔离 |
| Dynamic Feature / Split APK | 随宿主 | 同 App | 第一方重 native、共享 .so |
| AAR / 源码内置 | 否 | 宿主 | 核心能力、随宿主发版 |
| `.aibox` 数据/声明包 | 否（导入） | 无代码 | 模型、LoRA、工作流、主题、语音 |
| `.aibox` WASM 包 | 否（导入） | 宿主沙箱 | 轻量工具、可移植逻辑 |
| `.aibox` native payload | 否（可信） | 宿主进程 | 第一方运行时二进制、模型 |
| 裸 `.so` / 外部 dex | 否 | 不可控 | 不作为常规模块形态 |

#### 4.8.2 推理框架模块怎么选

```
第三方推理框架插件        → 独立 APK
第一方推理框架、重 native → Dynamic Feature / AAR
运行时二进制 / 模型       → .aibox payload
```

原因：

- 推理框架包含 native、JNI、线程池、GPU/NPU context、长生命周期 Service
- 需要独立进程、签名、权限、升级、崩溃隔离
- 纯 `.aibox` 可执行模块没有 UID/进程/权限隔离，native 崩溃会拖垮宿主
- `dlopen` 从可写目录受限，ABI/页对齐/依赖/JNI_OnLoad 都要自己处理

示例：

| 场景 | 形态 |
|---|---|
| 第三方 MNN 后端 | `mnn-backend.apk` + AIDL Service |
| 第一方 MNN | `:mnn` dynamic feature，共享 `.so` |
| MNN 运行时包 | `.aibox` 里放 `libMNN.so` + manifest |
| LiteRT / ONNX 第三方 | 独立 APK |
| NPU runtime | 宿主 native service 或 dynamic feature |

#### 4.8.3 `.aibox` 容器

```
module.aibox
  ├─ manifest.json
  ├─ payload/
  │    ├─ model.gguf
  │    ├─ lora.safetensors
  │    ├─ workflow.json
  │    ├─ module.wasm
  │    └─ lib/arm64-v8a/libfoo.so
  ├─ assets/
  ├─ signatures/
  │    ├─ manifest.sig
  │    └─ payload.sig
  └─ checksums.json
```

manifest 示例：

```json
{
  "schemaVersion": 1,
  "id": "com.example.sd.bundle",
  "type": "model-bundle",
  "version": "1.0.0",
  "capabilities": ["image.generate"],
  "payload": [
    {"role": "diffusion", "path": "payload/model.safetensors", "sha256": "..."},
    {"role": "vae", "path": "payload/vae.safetensors", "sha256": "..."}
  ],
  "dependencies": {
    "modules": [{"id": "com.aibox.backend.mnn", "required": true}]
  },
  "signature": {"alg": "ed25519", "keyId": "..."}
}
```

类型：

```
model-bundle
lora-pack
workflow-pack
theme-pack
voice-pack
tool-pack
wasm-plugin
native-payload（仅可信）
apk-payload（交给系统安装）
```

要求：

- ZIP64，大模型支持
- `.so` / 大文件 store，不压缩
- 每文件 SHA-256
- 整体 Ed25519 签名
- 拒绝 `..`、绝对路径、符号链接
- 限制 zip bomb / 解压总量
- ABI 选择
- 版本、依赖、冲突、能力声明
- 可选加密（AES-GCM / age）
- 可选内容寻址与增量

#### 4.8.4 混合形态

想让推理框架独立更新，又不想自建安装体系：

```
framework-plugin.apk
  ├─ AndroidManifest / Service / 权限
  ├─ assets/runtime.aibox
  │    ├─ manifest.json
  │    ├─ lib/arm64-v8a/libMNN.so
  │    └─ signatures/
  └─ AIDL
```

- APK：安装、签名、权限、进程、Service
- `.aibox`：运行时二进制、模型、版本
- 更新：换 payload 或整体升级 APK

#### 4.8.5 决策规则

```
第三方、完整可执行、需要隔离 → APK
第一方、重 native、要共享 .so → Dynamic Feature / AAR
模型 / LoRA / 工作流 / 主题 → .aibox 数据包
轻量可移植逻辑 → .aibox + WASM
可信运行时二进制 → .aibox native payload
裸 .so / 外部 dex / 脚本替代应用 → 不推荐
```

#### 4.8.6 反面例子

| 做法 | 问题 |
|---|---|
| SD diffusion `.so` 拆独立 APK | 每步 Binder，性能灾难 |
| MNN backend 做 AAR | 无法独立更新/第三方分发 |
| LoRA 做 APK | 无代码，浪费 |
| 每个插件自带 NPU runtime | 内存翻倍、版本冲突 |
| B 直接 dlopen C 的 `.so` | 跨 APK 不可行 |
| 外部 dex 当插件 | Android 限制、安全风险 |

---

### 4.9 模块崩溃日志

#### 4.9.1 边界

- 宿主崩溃捕获器抓不到独立插件进程
- 独立插件必须自己安装 Java/Native 崩溃捕获
- 宿主通过预先打开的 fd 接收日志
- ANR 由系统判定，宿主只能通过超时/Binder death 间接发现
- `ApplicationExitInfo` 主要可靠用于宿主自身；不要假设能读别的包

#### 4.9.2 推荐：宿主传 crash fd

```
宿主：
  files/plugin-crashes/<pkg>-<session>.log
  open -> ParcelFileDescriptor
  bind 时 attachCrashSink(fd)

插件：
  保存 fd
  Java 崩溃 -> 写 stack trace
  Native 崩溃 -> signal handler write(fd)
  fsync
```

AIDL：

```
void attachCrashSink(in ParcelFileDescriptor fd)
void detachCrashSink()
```

#### 4.9.3 Java/Kotlin

- 在 Application/Service onCreate 尽早安装 `UncaughtExceptionHandler`
- 写 moduleId / version / hostVersion / backend / modelId / 当前任务
- flush/fsync
- 交回系统默认 handler，不吞异常

#### 4.9.4 Native

- signal handler 捕获 SIGSEGV/SIGABRT/SIGBUS/SIGILL/SIGFPE
- 只做 async-signal-safe：`write` / `fsync` / `_exit`
- 不 malloc、不 printf、不加锁、不调 Binder/JNI
- 记录 signal、fault address、ABI、build id、最近操作
- 完整 backtrace 交给 tombstone/debuggerd

#### 4.9.5 ANR / Binder death

- 每次 Binder 调用带超时
- 超时标记 `UNRESPONSIVE`
- `linkToDeath` + `onServiceDisconnected`
- 记录最后请求、等待时长、心跳、宿主调用栈
- 不无限重绑，避免崩溃循环
- 真 ANR trace 需要 `adb` / `bugreport`

#### 4.9.6 诊断包

```
host log
plugin crash log
device info
module list + versions
backend/model state
memory/thermal snapshot
```

#### 4.9.7 测试矩阵

- Java 崩溃
- Native SIGSEGV
- ANR / Binder 超时
- 服务进程被杀
- 崩溃 fd 写满/关闭
- 崩溃后回退
- 导出诊断包


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
