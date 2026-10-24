# AIBox 贡献指南

## 提交前自检（不可跳过）

```bash
# 1. 静态检查（Detekt 严格模式）
./gradlew detekt

# 2. 单元测试
./gradlew testDebugUnitTest

# 3. 编译验证
./gradlew assembleDebug
```

任何步骤失败禁止 PR 合入。

## 代码审查清单

- [ ] 单向依赖：`app → feature → core → inference-api`，无反向引用
- [ ] 业务错误用 `Outcome.Failure`，无异常抛出
- [ ] 数据类全部 `data class`，状态修改用 `copy()`
- [ ] 单例通过 Hilt 注入，无手写 `object` 或 `companion object instance`
- [ ] 日志通过 `AILogger` 写入，无 `android.util.Log` / `println`
- [ ] KDoc 覆盖所有 `public` 函数和接口
- [ ] 长度限制：单方法 < 80 行，单类 < 150 行（Detekt 强制）

## NPU 后端开发规范

新增 NPU 后端时，必须：

1. 实现 `InferenceBackend` 接口，**禁止修改接口本身**
2. 在 `backends/<vendor>/` 下独立 Gradle 模块
3. 厂商 SDK 通过 `fileTree` 引入（不提交 SDK 本体到 git）
4. `backends/<vendor>/README.md` 必须列出：
   - 支持的 SoC 型号
   - Hexagon 版本
   - 模型转换工具
   - 许可证类型
5. 单元测试覆盖：检测逻辑、fallback 路径

## 插件开发规范

新增插件 APK 时，必须：

1. AIDL 接口与 `core/plugin-host/src/main/aidl/` 原样复制
2. Manifest 声明 Intent Action + `com.aibox.permission.REGISTER_PLUGIN`
3. 实现签名（开发期可加入宿主白名单）
4. Service 不允许引用宿主任何 class（只能通过 AIDL）
5. 插件 Manifest 内 `aibox_api_version` 必须匹配宿主当前版本

## 日志规范

| 等级 | 使用场景 |
|------|----------|
| VERBOSE | 推理 token 流、细粒度调试 |
| DEBUG | 模块边界、关键状态切换 |
| INFO | 启动/关闭、用户可见事件 |
| WARN | 非致命错误（fallback 触发） |
| ERROR | 失败但可恢复（推理错误） |
| CRASH | 未捕获异常（仅由系统调用） |

## 提交格式

```
<type>(<scope>): <subject>

<body>

<footer>
```

类型：`feat` / `fix` / `refactor` / `docs` / `chore` / `test`
scope：模块名（如 `inference-api`、`plugin-host`）

## 行为准则

- 友善、包容、对事不对人
- 优先尊重架构决策，重大改动必须先开 issue 讨论
- 不接受"快速 hack"风格的 PR（违背反屎山哲学）
