# 变更记录 2026-11-20 · 内存缓冲 / 体感温度 / 角色扮演

## 背景

依据用户提供的 `aibox-2026-11-20.log`（9 次会话）定位问题。日志关键证据：

- 反复出现 `内存不足：模型约 4409MB + 运行缓冲 3057MB，当前可用仅 5401~7509MB`，
  Ling-3.0-tiny Q4_0（4409MB）多次被预检拦截，加载失败。
- `ThermalMonitor start: sysfs_zones=0` —— 该机（Magic6 / Android 16）没有可读 sysfs 热区，
  体感温度（skin）采集全程返回 -1，日志中找不到任何体感温度记录。
- 附带：`apply_hexagon_session_env: HTP0:0,HTP0:1,HTP0:2` 已设置，但
  `allocating new registry : ndev 1` + `backend=npu -> 1 device(s)` + 所有层都在 HTP0，
  说明 hexagon registry 在探测阶段就按 ndev=1 固化，多虚拟会话本次未真正生效（见"遗留"）。

## 改动

### 1. 运行缓冲降到约 1GB（`domain/inference/MemoryGuard.kt`）

- 旧：`bufferMb = max(512, totalMem/5)` → 16GB 机器算出 3057MB，随总内存放大，过于保守。
  GGUF 权重走 mmap 按需换页，加载期真正额外占用只有 KV cache + compute buffer。
- 新：固定 `RUN_BUFFER_MB = 1024`（约 1GB），覆盖 8K 上下文 Q8_0 KV + batch 512 compute + 系统余量。
  4409MB 模型 + 1024MB = 5433MB，在 7GB 可用下可顺利通过预检。
- 加载后仍有 `postLoadProblem()`（95% 占用阈值）按实际占用复核兜底，预检放宽是安全的。

### 2. 体感温度补全（`util/ThermalMonitor.kt`）

- `readSkinCelsius()` 增加**电池温度兜底**：skin/case 等 sysfs 热区 → hwmon 壳体传感器 →
  电池温度（紧贴机身的体表代理）。解决 `sysfs_zones=0` 时体感档永不出现的问题。
  明确与"芯片结温"区分，注释说明为何电池温度是合理的体表代理。
- 采集来源记入 `skinSource`；温度来源/数值（0.5℃ 粒度）变化时打一条日志
  `体感温度: xx℃ (来源=...)`，可追溯且不会 2 秒刷屏。
- 字段 KDoc 更新，补全采集优先级说明。UI 侧（ChatScreen）原有七档文案逻辑无需改动即可显示。

### 3. 角色扮演完善（`ui/roleplay/RoleplayScreen.kt`）

- **两列布局**：角色列表从单列改为两列网格（手动 chunked(2) + Row + weight，
  避免在 LazyColumn 内嵌 LazyVerticalGrid 导致滚动冲突）。
- **2:3 竖卡片**：每张角色卡 `aspectRatio(2f/3f)`，上半卡片图（无图 emoji 占位）、
  下半头像+名字+简介+"开始聊天" + 编辑铅笔。奇数个角色补等宽占位保持对齐。
- **独立添加/编辑页**：原 AlertDialog 弹窗改为全屏 `CharacterEditorPage`，字段更完整、
  顶部有 2:3 实时卡片预览、保存/删除都在页内；右上角 ＋ 或卡片铅笔进入。
- 沿用现有中性主题色（仅语义色/主色容器），无蓝色强调色；保留 SAF 选图持久化、
  头像共用/独立、性格/世界观/系统提示/开场白全部字段。

## 编译与交付

- 走 hosted NPU job（build-apk.yml `build_npu_hosted`），llama.cpp 维持 v1.0.0。
- APK 交付路径见最终回复。

## 遗留（本次未改，需实机确认）

- **多虚拟会话未生效**：日志显示 registry 在首次探测即按 ndev=1 固化。当前模型 4.4GB 在
  单会话（vmem≈3.35GB）+ 单 NPU 上已能加载成功（17:08:22 `llamaLoad: ready`），
  说明 v1.0.0 的单会话动态 map/unmap 已能覆盖该体积；多会话设置对更大模型（9B+）的
  实际收益需在真机确认 registry 是否能按 GGML_HEXAGON_DEVICES 重建。若确认无效，
  下一步应改用进程启动首帧前设置 env（早于任何 backend 探测）的路径。
