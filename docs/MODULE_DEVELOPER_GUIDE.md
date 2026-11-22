# AIBox 模块开发与格式指南

> 面向要写 AIBox 插件/模块的工程师。
> 目标：**上手简单、管理简单、默认安全**。
> 完整 v2 契约见 [PLUGIN_MODULE_SPEC_V2.md](PLUGIN_MODULE_SPEC_V2.md)。

---

## 1. 先选形态

| 你要做的东西 | 形态 | 例子 |
|---|---|---|
| 带代码、独立安装、需要隔离 | **APK 插件** | MNN 后端、SD 功能模块、工具插件 |
| 第一方重 native、要共享 `.so` | **Dynamic Feature / AAR** | 宿主内置 MNN、NPU runtime |
| 模型 / LoRA / VAE / 词表 | **`.aibox` 数据包** | SD 模型包、LoRA 包 |
| 工作流 / prompt / 主题 / 语音 | **`.aibox` 数据包** | 角色卡、语音包 |
| 轻量可移植逻辑 | **`.aibox` + WASM** | 小工具、格式转换 |
| 可信 native 二进制 | **`.aibox` native payload** | 第一方运行时 |

一句话：

```
第三方完整插件 → APK
模型和内容 → .aibox
第一方重 native → Dynamic Feature / AAR
```

不要用 `.aibox` 替代 APK 做第三方可执行插件。

---

## 2. APK 插件最小结构

```
my-plugin/
  settings.gradle.kts
  app/build.gradle.kts
  app/src/main/AndroidManifest.xml
  app/src/main/aidl/com/aibox/plugin/*.aidl   ← 与宿主字节级一致
  app/src/main/assets/module_manifest/manifest.json
  app/src/main/kotlin/.../PluginService.kt
```

### 2.1 AndroidManifest

```xml
<service
    android:name=".PluginService"
    android:exported="true"
    android:process=":plugin">
    <intent-filter>
        <action android:name="com.aibox.action.BACKEND_PLUGIN" />
    </intent-filter>
    <meta-data
        android:name="aibox.descriptor_class"
        android:value="com.example.plugin.PluginDescriptor" />
    <meta-data
        android:name="aibox.minVersion"
        android:value="v261119-1" />
</service>
```

要求：

- `exported=true`，否则宿主发现不了
- 独立进程 `:plugin`，崩溃不拖垮宿主
- 只暴露一个入口 Service
- 不要申请无关权限

### 2.2 模块 manifest

`assets/module_manifest/manifest.json`：

```json
{
  "schema_version": "1.0.0",
  "module_id": "com.example.mnn.backend",
  "version": "1.0.0",
  "module_type": "backend_provider",
  "min_host_api": 2,
  "abi_filters": ["arm64-v8a"],
  "exports": ["aibox_mnn_v1"],
  "capabilities": ["backend.mnn", "npu.prefill"],
  "native_libs_bundled": [
    {
      "soname": "libMNN.so",
      "file_name": "libMNN.so",
      "abi": "arm64-v8a",
      "sha256": "..."
    }
  ]
}
```

规则：

- `module_id` 小写点分，全局唯一
- `version` 语义化版本
- `min_host_api` 不要虚高
- `exports` 必须匹配 `^aibox_[a-z][a-z0-9_]*_v\d+$`
- 第三方插件**不要**声明依赖别的插件的 `.so`
- 需要别的插件能力时，用 `capabilities` + 服务调用，不要 `depends_on_libs`

### 2.3 服务实现

- 实现 `IPluginInterface.Stub`
- `loadModel` / `infer` / `cancel` / `unload` 都要有超时和错误码
- 大文件只用 `ParcelFileDescriptor`
- 日志通过宿主注入的 callback，不要自己写全局文件
- 安装 `UncaughtExceptionHandler`
- 暴露能力前先自检 native 是否加载成功

---

## 3. `.aibox` 数据包

### 3.1 结构

```
sd-model.aibox
  ├─ manifest.json
  ├─ payload/
  │    ├─ diffusion.safetensors
  │    ├─ vae.safetensors
  │    └─ text_encoder.safetensors
  ├─ checksums.json
  └─ signatures/
       └─ manifest.sig
```

### 3.2 manifest

```json
{
  "schema_version": 1,
  "id": "com.example.sd.model",
  "type": "model-bundle",
  "version": "1.0.0",
  "name": "SD 1.5 本地模型",
  "payload": [
    {
      "role": "diffusion",
      "path": "payload/diffusion.safetensors",
      "sha256": "...",
      "required": true
    },
    {
      "role": "vae",
      "path": "payload/vae.safetensors",
      "sha256": "...",
      "required": true
    }
  ],
  "dependencies": {
    "modules": [],
    "capabilities": ["image.generate"]
  }
}
```

### 3.3 类型

```
model-bundle
lora-pack
workflow-pack
theme-pack
voice-pack
tool-pack
wasm-plugin
native-payload
apk-payload
```

### 3.4 校验

- 必须有 `manifest.json`
- 每个 payload 必须有 `path`
- 有 `sha256` 就校验
- 路径禁止绝对路径、`..`、反斜杠、符号链接
- 限制条目数、单文件大小、解压总量
- `wasm-plugin` / `native-payload` / `apk-payload` 默认要求签名
- 验签器由宿主提供；未配置时只能标记“未校验”，不能当成已验签

### 3.5 例子：LoRA 包

```json
{
  "schema_version": 1,
  "id": "com.example.lora.anime",
  "type": "lora-pack",
  "version": "1.0.0",
  "payload": [
    {"role": "lora", "path": "payload/anime.safetensors", "sha256": "..."}
  ]
}
```

### 3.6 例子：WASM 工具

```json
{
  "schema_version": 1,
  "id": "com.example.tool.weather",
  "type": "wasm-plugin",
  "version": "1.0.0",
  "payload": [
    {"role": "wasm", "path": "payload/weather.wasm", "sha256": "..."}
  ]
}
```

必须签名；宿主用 WASM 沙箱执行，限制网络/文件/CPU/内存。

---

## 4. 模块管理状态

```
INSTALLED    已安装
DISCOVERED   已发现
ENABLED      已启用
BOUND        已绑定
READY        可用
BUSY         忙碌
IDLE         空闲
UNLOADED     已释放
BLOCKED      缺必选前置/被禁用/不兼容
```

UI 只展示用户能理解的：

```
已安装 · 未启用
已启用 · 空闲（60s 后释放）
缺少前置：MNN 后端
不兼容：targetSdk 过低
```

管理原则：

- 安装 ≠ 启用
- 启用 ≠ 绑定
- 绑定 ≠ 加载模型
- 默认按需，空闲释放

---

## 5. 依赖规则（保持简单）

### 5.1 必选前置

- 缺失 → 模块不能启用
- 安装/启用时展示依赖、大小、权限、来源
- 用户确认后安装
- 不允许静默安装
- 运行中被卸载 → 立即 suspend、取消任务

### 5.2 可选前置

- 不阻塞安装/启动
- 只有调用相关能力时才提示
- 必须声明 fallback：`cpu` / `disable_feature`
- 支持“不再提醒”
- 不能每次点击都弹

### 5.3 不要做的事

- 不要跨 APK 依赖别人的 `.so`
- 不要让依赖链超过一层
- 不要自动下载/安装依赖
- 不要用依赖制造隐藏权限
- 不要循环依赖

需要别的插件能力时：

```json
"dependencies": {
  "modules": [
    {"id": "com.example.mnn.backend", "required": true, "prompt_at": "install"}
  ],
  "capabilities": [
    {"id": "backend.mnn", "required": true}
  ]
}
```

调用走 Binder/工具网关，不共享 native 库。

---

## 6. 安全底线

### 6.1 信任等级

| 等级 | 来源 | 策略 |
|---|---|---|
| first-party | 宿主签名/内置 | 可放宽 |
| verified | 已验证开发者 | 允许安装，仍沙箱 |
| third-party | 普通第三方 | 最小权限，默认拒绝危险能力 |
| dev | debug 构建 | 仅 debug 宿主允许 |

### 6.2 启用前检查

- 必须有签名
- release 宿主默认拒绝 `debuggable` 插件
- `targetSdk >= 26`
- 至少一个 exported Service
- `module_id` / 版本合法
- manifest 未知字段拒绝
- ABI 匹配

### 6.3 运行时

- 独立进程
- 不直接访问宿主数据库/任意文件
- 文件通过 URI / fd / 宿主 API
- 大文件零拷贝
- 网络默认拒绝
- 日志脱敏：不记录 prompt 原文、图片、密钥
- 资源上限：内存、线程、并发、超时
- 崩溃不拖垮宿主

### 6.4 `.aibox` 安全

- 拒绝 zip-slip / 绝对路径 / 反斜杠 / 符号链接
- 限制条目数、单文件大小、解压总量
- SHA-256 校验
- 代码类 payload 必须签名
- native payload 只允许可信来源
- 解压到宿主私有目录，校验后再加载
- 不执行模型内脚本

### 6.5 恶意代码防范

- 不动态加载外部 dex/jar
- native 只从 APK/已验签 payload 加载
- 不把任意路径交给插件
- 不把 Master Key / 保险箱密钥交给插件
- 工具调用需要 schema 校验和审计
- 写操作、删除、联网每次确认
- 结果大小上限，防注入

---

## 7. 崩溃日志

独立插件必须自己捕获：

- Java `UncaughtExceptionHandler`
- Native signal handler
- 宿主通过 `ParcelFileDescriptor` 提供 crash sink
- 宿主 `linkToDeath` 监听服务死亡
- 诊断包导出：宿主日志、插件崩溃日志、设备/模块/后端状态

详见 v2 规范 4.9。

---

## 8. 编写检查清单

### APK 插件

- [ ] 独立 applicationId / 签名
- [ ] 一个 exported 入口 Service
- [ ] `aibox.minVersion` 正确
- [ ] `assets/module_manifest/manifest.json` 合法
- [ ] 只申请必要权限
- [ ] 独立进程
- [ ] 安装崩溃 handler
- [ ] 所有 AIDL 方法有超时/错误码
- [ ] 大文件用 fd
- [ ] 不依赖别的插件 `.so`
- [ ] 卸载能清理

### `.aibox` 包

- [ ] `schema_version=1`
- [ ] id/version 合法
- [ ] payload 路径安全
- [ ] 每个 payload 有 sha256
- [ ] 代码类 payload 有签名
- [ ] 大小/条目数在限制内
- [ ] 不含可执行脚本（除非 WASM 沙箱）

---

## 9. 常见错误

| 错误 | 后果 |
|---|---|
| 跨 APK dlopen 别人的 `.so` | 加载失败 |
| 一个 `.so` 一个 Service | Binder 爆炸、性能差 |
| 把 LoRA 做成 APK | 浪费、管理复杂 |
| release 宿主允许 debuggable 插件 | 安全风险 |
| 插件自己写全局日志文件 | 宿主读不到、隐私风险 |
| 依赖自动安装 | 用户不可控 |
| 无签名 `.aibox` 执行 native | 供应链风险 |
| 动态加载外部 dex | Android 限制/审核风险 |

---

## 10. 推荐默认值

```
第三方插件：APK + 独立进程 + 最小权限
模型/内容：.aibox + SHA-256
代码 payload：.aibox + 签名
第一方重 native：Dynamic Feature / AAR
依赖：必选阻断，可选懒提示
后端：默认按需开启，空闲释放
日志：宿主 crash sink
```

---

## 11. 相关文档

- [PLUGIN_DEVELOPMENT.md](PLUGIN_DEVELOPMENT.md)：现有 AIDL 插件开发
- [PLUGIN_MODULE_SPEC_V2.md](PLUGIN_MODULE_SPEC_V2.md)：v2 模块/依赖/安全/崩溃规范
- `app/src/main/assets/module_manifest/schema-v1.json`：manifest schema
