# Umbra Benchmark

这是一套面向 Umbra 真机与虚拟屏架构的本地 Benchmark。它借鉴
[AndroidWorld](https://github.com/google-research/android_world) 的参数化任务、状态型
reward、重复运行与成功率统计，以及
[AndroidLab](https://github.com/THUDM/Android-Lab) 的任务集/执行器/结果生成分层，但不要求
固定 AVD 或下载整套测试镜像。

当前版本先解决三件事：

1. 同一批任务可以在同一设备、同一模型配置下重复运行。
2. Agent 的终态、动作、步数、反思、验证失败和耗时可以自动比较。
3. 暂时无法可靠读取目标 App 内部状态的任务，可以由人只判断最终页面，而不用人工整理日志。

## 快速开始

前提：

- 手机通过 USB ADB 连接并授权。
- Umbra 设置中已经配置可用的多模态模型。
- Umbra 无障碍服务已启用。
- 运行虚拟屏案例前，Shizuku 已启动并授权。
- 本机保留 Release keystore 时，`benchmark` 构建会沿用正式签名并覆盖安装，不清除 App 数据。

先验证和查看任务，不连接手机也能运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
  .\tools\benchmark\run-benchmark.ps1 -ValidateOnly

powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
  .\tools\benchmark\run-benchmark.ps1 -List
```

第一次运行或代码更新后，构建并安装专用 Benchmark APK，然后执行 smoke suite：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
  .\tools\benchmark\run-benchmark.ps1 -BuildAndInstallBenchmark
```

只跑一个任务、重复三次：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
  .\tools\benchmark\run-benchmark.ps1 `
  -TaskId virtual-open-qq -Repeat 3
```

无人值守运行，不弹出人工判定问题：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
  .\tools\benchmark\run-benchmark.ps1 -NonInteractive
```

这会把需要人工 oracle 的任务标成 `UNVERIFIED`，而不是错误地计为成功。

> Benchmark 会真实操作手机并调用模型，可能产生 API 费用。请先使用低风险任务，支付、发送、
> 删除和隐私数据操作不应放入自动运行的公开 suite。

## 为什么需要专用构建

正式 APK 不暴露外部任务注入接口。`benchmark` 与 `debug` 构建才包含
`BenchmarkCommandReceiver`，并使用 `android.permission.DUMP` 限制为 ADB shell/system 调用。
任务文本以 Base64 传输，API Key 始终从 App 私有设置读取，不经过电脑命令行或结果文件。

`benchmark` 构建是 debuggable，只应用于本地测试，不要分发。测试结束后覆盖安装正式 APK：

```powershell
X:\Android\sdk\platform-tools\adb.exe install -r `
  .\artifacts\umbra-phone-agent-v2.0.0\umbra-phone-agent-v2.0.0.apk
```

如果本机没有原 Release keystore，Benchmark APK 会退回 Debug 签名，无法覆盖正式 APK。runner
不会自动卸载应用，因为卸载会清除 API Key、聊天记录和其他 App 数据。

## 结果目录

每次运行创建一个独立目录：

```text
benchmark-results/umbra-smoke-v1-YYYYMMDD-HHMMSS/
  suite.json
  summary.md
  summary.csv
  summary.json
  runs/<task-id>-r<repeat>-<timestamp>/
    events.jsonl
    result.json
```

- `events.jsonl`：完整结构化 Agent 事件，适合定位具体哪一步失败。
- `result.json`：单次运行的动作、终态和每条 oracle 检查结果。
- `summary.csv`：适合导入 Excel 画图。
- `summary.md`：最容易直接阅读或贴回 issue。

## 评分口径

结果有三层，不能混为一谈：

- `auto_pass`：日志协议与配置的 oracle 是否通过，例如终态、动作、工具、包名、步数和反思上限。
- `manual_status`：需要观察最终页面时，由人填写 `PASS`、`FAIL` 或 `UNVERIFIED`。
- `status`：自动检查失败或人工失败为 `FAIL`；自动通过且不需人工/人工通过为 `PASS`；缺少人工结果为 `UNVERIFIED`。

汇总中的 `auto_pass_rate` 衡量执行管线是否满足自动规则；`verified_success_rate` 只在明确的
`PASS/FAIL` 上计算，不把 `UNVERIFIED` 当作成功。

当前 `package_regex` 从 Agent 的 `PERCEPTION` 日志读取前台包名，因此也能覆盖虚拟屏。如果目标
App 不暴露包名或感知层没有拿到包名，该项会失败，这本身是一个可定位的感知问题。

## 填写自己的任务

复制 `benchmarks/suites/template.json`，修改 `name`，把案例的 `enabled` 改成 `true`。格式由
`benchmarks/schema/umbra-benchmark.schema.json` 定义。

推荐每个任务填写：

- `id`：稳定、不可重复的英文标识。
- `instruction`：用户真实会说的原始命令，不要写执行步骤提示模型。
- `mode`：`MAIN_SCREEN` 或 `VIRTUAL_DISPLAY`。
- `required_packages`：缺少 App 时自动 `SKIP`，避免把环境问题算成 Agent 失败。
- `repetitions`：正式比较建议至少 3 次；关键任务建议 5 次。
- `oracle.terminal_phases`：允许 `COMPLETE`、`TAKEOVER` 或明确测试失败处理时的 `FAILED`。
- `required_actions` / `required_system_tools`：检查是否走了预期的稳定路径。
- `package_regex`：最终应看到的目标包名。
- `max_steps` / `max_reflections` / `max_failed_verifications`：效率与稳定性预算。
- `manual_prompt`：只问一个可以观察、可以回答“是/否”的最终状态问题。

建议先共同补齐以下分层：

1. L0 协议：Launch、Wait、Back、Take_over 与各系统工具。
2. L1 单应用：搜索、输入、切换页面，不产生外部副作用。
3. L2 跨页面：打开应用、搜索、选择目标、停在确认页。
4. L3 恢复：弹窗、加载慢、错误点击、页面循环与重新定位。
5. L4 主屏/虚拟屏一致性：同一任务分别在两个显示目标上运行。

为了保持结果可比较，每条任务还应记录初始状态、账号/地区依赖、允许的副作用，以及人工如何把
设备恢复到下一次运行的起点。后续可以为稳定测试 App 增加真正的状态查询 oracle，而不是依赖
模型自报完成或人工目测。
