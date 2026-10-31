# Easy Chat Module

AIBox 第一个独立模块 APK。定位"零配置即用":模块内自带 Qwen2.5-0.5B-Instruct Q4\_K\_M 模型,
安装到设备后宿主 AIBox 自动发现并加载,无需用户手动下载模型或配置参数。

## 模块定位

| 项目 | 值 |
| --- | --- |
| 模块名 | `easy-chat` |
| 显示名 | Easy Chat |
| 类型 | `chat`(不是 Agent;Agent 是 LFM2.5-350M 独立产品线) |
| 内置模型 | Qwen2.5-0.5B-Instruct Q4\_K\_M(GGUF) |
| 模型大小 | ~485 MB |
| 架构 | `qwen2` |
| 最低 llama.cpp 版本 | b2000 |
| 推理框架 | **不在模块内打包**,复用宿主 AIBox 的 InferenceBackend(llama.cpp) |

模块只负责四件事:

1. 把模型文件打包到 `assets/`;
2. 通过 `assets/module.json` 暴露结构化元数据;
3. 通过 `AndroidManifest.xml` 的 `<service>` + `<intent-filter>` 暴露入口;
4. 安装后让宿主 PackageManager 自动发现 → bindService → 读 module.json → 复制模型 → 加载。

## 工程结构

```
easy-chat-module/
├── settings.gradle.kts               # 独立 Gradle 工程,rootProject.name = EasyChat
├── build.gradle.kts                   # 顶层,声明插件版本
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── README.md                          # 本文件
├── .gitignore
└── app/
    ├── build.gradle.kts               # AGP + Kotlin,namespace=com.easychat
    ├── proguard-rules.pro             # 保 AIDL Stub / Service / Descriptor 不被混淆
    └── src/main/
        ├── AndroidManifest.xml        # Service + meta-data
        ├── assets/
        │   ├── module.json            # ★ 宿主读取的权威元数据
        │   └── .gitkeep               # 占位,真实 .gguf 由 CI 注入
        ├── aidl/com/aibox/plugin/
        │   └── IChatModuleInterface.aidl
        └── kotlin/com/easychat/
            ├── EasyChatModuleService.kt     # onBind 返回 Binder
            ├── EasyChatModuleBinder.kt      # IChatModuleInterface.Stub 实现
            └── EasyChatModuleDescriptor.kt  # meta-data 中引用的描述符
```

## 与宿主的通信契约

### 发现阶段(无需绑定)

宿主用 PackageManager 扫描 `action="com.aibox.action.CHAT_MODULE"` 的 Service,读到:

- `aibox.module.name` = `easy-chat`
- `aibox.module.displayName` = `Easy Chat`
- `aibox.module.version` = `1.0.0`
- `aibox.module.model.fileName` = `qwen2.5-0.5b-instruct-q4_k_m.gguf`
- `aibox.module.model.sizeMb` = `485`
- `aibox.module.model.architecture` = `qwen2`
- `aibox.module.model.recommendedBackend` = `vulkan,cpu`
- `aibox.module.model.avoidBackend` = `npu`

宿主据此预判 RAM / 后端兼容性,决定是否进入下一阶段。

### 绑定阶段

宿主 `bindService` 到本 Service,`onServiceConnected` 拿到 `IBinder`,
通过 `IChatModuleInterface.Stub.asInterface(binder)` 转接口,调用:

| 方法 | 返回 | 用途 |
| --- | --- | --- |
| `getModuleMetadata()` | `String` (JSON) | 拿 `assets/module.json` 全文,解析得到详细推荐配置 |
| `getModelFileSize()` | `Long` | 估算复制耗时与目标盘空间;模型未注入时返回 -1 |
| `openModelFileDescriptor()` | `ParcelFileDescriptor?` | 零拷贝读 assets,复制到宿主私有目录;模型未注入时返回 null |

宿主拿到模型文件本地路径后,unbindService,后续由宿主 `InferenceBackend.loadModel(path)` 接管。

## module.json 推荐配置摘要

| 字段 | 默认值 | 备注 |
| --- | --- | --- |
| `recommendedConfig.contextLength` | 1440 | 与 `model.architecture=qwen2` 的常用值对齐 |
| `recommendedConfig.batchSize` | 512 | prompt 处理批大小 |
| `recommendedConfig.kvCacheType` | q8\_0 | KV cache 量化以省 RAM |
| `recommendedConfig.kvCacheTypeV` | q8\_0 | V cache 同步量化 |
| `recommendedConfig.flashAttention` | true | Qwen2 受益明显 |
| `recommendedConfig.mmap` | true | 模型内存映射,降低 RAM 峰值 |
| `recommendedConfig.threads` | 2 | 默认;旗舰可升 4 |
| `recommendedConfig.backendPreference` | `["vulkan","cpu"]` | 优先 GPU,降级 CPU |
| `recommendedConfig.backendAvoid` | `["npu"]` | NPU 在 0.5B 上反向加速,显式禁用 |
| `model.noSystemPrompt` | true | 模块自带 chat template,宿主无需注入 system prompt |

设备分层:

| 分层 | SoE 最低 | RAM 最低 | threads | contextLength | 额外 |
| --- | --- | --- | --- | --- | --- |
| `low_end` | Snapdragon 4 Gen1 | 3000 MB | 2 | 1024 | — |
| `mid_range` | Snapdragon 6 Gen1 | 4000 MB | 4 | 1440 | — |
| `flagship` | Snapdragon 8 Gen2 | 6000 MB | 4 | 1440 | `preferredBackend="vulkan"` |

预期性能(实测于骁龙 8 Gen3 CPU 2 线程):

| 指标 | 值 |
| --- | --- |
| Real chat tg | 31 tok/s |
| Peak tg | 40 tok/s |
| Heavy load tg | 17 tok/s |
| TTFT | 2580 ms |

## 构建与模型注入

**重要**:本工程不在源码仓库中提交 485 MB 的 GGUF 模型(会撑爆 git 仓库且与许可冲突)。
构建时由 CI/CD 完成模型注入,本地开发可用占位文件验证宿主侧"模块未注入"的错误路径。

### 本地构建(无模型)

```bash
cd easy-chat-module
./gradlew :app:assembleRelease
# 输出:app/build/outputs/apk/release/app-release.apk
# 此时 getModelFileSize() 返回 -1,openModelFileDescriptor() 返回 null
# 宿主扫描能看到模块但加载会失败,用于验证"未注入"错误路径
```

### CI 注入模型

推荐做法(GitHub Actions):

```yaml
- name: Download model
  run: |
    mkdir -p app/src/main/assets
    curl -L -o app/src/main/assets/qwen2.5-0.5b-instruct-q4_k_m.gguf \
      https://github.com/<org>/mobile-ai-box/releases/download/qwen2.5-0.5b-v1/qwen2.5-0.5b-instruct-q4_k_m.gguf
- name: Build APK
  run: ./gradlew :app:assembleRelease
- name: Sign & publish
  run: ...
```

模型发布源头:`Qwen/Qwen2.5-0.5B-Instruct-GGUF`(Hugging Face)→ `q4_k_m` 量化版 → 团队镜像到 GitHub Releases。

## 零配置原则

模块安装到设备后,用户**不需要**做任何操作:

1. 宿主 PackageManager 监听 `PACKAGE_ADDED` / `PACKAGE_REPLACED` Intent;
2. 收到 `com.easychat.aibox.module` 安装事件后,扫描其 `<service>` intent-filter;
3. 命中 `com.aibox.action.CHAT_MODULE`,识别为 Easy Chat 模块;
4. 自动 bindService → 读 module.json → 复制模型 → 加载到内存;
5. 用户打开 AIBox 主界面时,"Easy Chat"已就绪。

唯一需要用户感知的:首次安装时 PackageManager 可能弹"该模块需要权限?"提示——本模块不申请任何权限,
所以提示会消失或显示"无需权限"。

## 与宿主工程的差异

| 项目 | AIBox 宿主 | Easy Chat 模块 |
| --- | --- | --- |
| `applicationId` | `com.aibox` | `com.easychat.aibox.module` |
| `namespace` | `com.aibox` | `com.easychat` |
| 打包推理框架 | 是(C++ + JNI) | **否**,复用宿主 |
| 模型文件来源 | 用户下载 / 内置 | 模块 assets 内置 |
| AIDL 角色 | 实现侧(Stub)+ 调用侧 | 实现侧(Stub)+ 调用侧 |
| Activity | 有(主界面) | 无(纯模块) |

## 许可

MIT,见项目根 LICENSE。
