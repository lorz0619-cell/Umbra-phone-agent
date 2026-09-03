# Umbra phone-agent

Umbra 是一个运行在真实 Android 手机上的任务型 Agent。它既可以操作主屏，也可以通过 Shizuku 在独立虚拟屏中执行任务，让用户继续使用主屏。

Android App 使用 Kotlin StateGraph 编排通用 OpenAI-compatible 多模态模型。动作规划、参数校验、执行、后置验证、反思与熔断都在本地完成。

## Architecture

### 分层架构

```mermaid
flowchart TB
    UI["Compose UI<br/>持久多轮对话 / 设置"] --> Timeline[("追加式会话时间线<br/>SharedPreferences")]
    UI --> Router{"Conversation Router<br/>无工具调用"}
    Timeline --> Router
    Router -->|普通聊天| Timeline
    Router -->|明确的手机任务| Service["AgentService<br/>生命周期与状态流"]
    Service -->|最终总结 / 报错| Timeline
    Service --> Graph["AgentGraph<br/>Kotlin 共享状态 + 显式边"]
    Service --> Notice["常驻通知控制<br/>状态 / 文字与语音命令 / 停止 / 接管"]
    Notice --> Voice["Vosk 离线中文语音<br/>首次下载模型 / 本地识别"]
    Voice --> Router

    subgraph Runtime["Agent Graph Runtime"]
        P["Perception Node<br/>截图 + 无障碍树 + 包名 + 输入焦点"]
        B["Bootstrap Node<br/>明确应用请求优先 Launch"]
        L["Planning Node<br/>通用 VLM function calling"]
        V["Validation Node<br/>工具白名单 / 参数 / 坐标 / 风险分级"]
        E["Execution Node<br/>强类型 DeviceAction"]
        C["Verification Node<br/>变化 / 输入 / 前台校验"]
        R{"Routing Node<br/>成功 / 重规划 / 熔断 / 结束"}
        F["Reflection Guard<br/>失败证据 / 循环检测 / 副作用去重"]
        RP["Reflection Perception<br/>强制刷新截图与语义树"]
        RL["Recovery Planner<br/>失败归因 + 实质策略变化"]
        TA["Terminal Adjudicator<br/>Take_over 或明确终止"]
        P --> B
        B -->|无本地启动动作| L --> V --> E --> C --> R
        B -->|本地匹配到已安装应用| V
        R -->|继续| L
        R -->|需要新快照| P
        R -->|卡死或语义循环| F
        F --> RP --> RL --> V
        RL -->|持续无法恢复| TA
        TA -->|人工仍可继续| HOLD["保留虚拟屏 / 等待确认"]
        TA -->|接管无意义| END([END])
        R -->|完成| END
    end

    Graph --> P
    L <-->|普通规划：非思考 + 强制 function call| Provider[("任意兼容 VLM Provider")]
    RL <-->|专用反思提示 + 失败原因 / 策略变化| Provider
    Router <-->|JSON 聊天 / 意图路由| Provider

    E --> Dispatch{"动作类型"}
    Dispatch -->|视觉动作| Platform{"AgentPlatform"}
    Dispatch -->|低 / 中风险系统工具| Intent["SystemCapabilityExecutor<br/>固定 Android Intent 白名单"]
    Intent --> Platform
    P --> Platform
    Platform --> Main["主屏<br/>AccessibilityService"]
    Platform --> Virtual["虚拟屏<br/>Shizuku + ImageReader"]
    Virtual -.隔离边界保持不变.-> MainScreen[("用户主屏继续使用")]
    HOLD -->|用户允许| Move["Shizuku moveRootTaskToDisplay<br/>现有任务迁移到主屏"]
    Move --> MainScreen
```

### Agent 状态机

```mermaid
stateDiagram-v2
    [*] --> Perceive
    Perceive --> Plan: 获得截图与无障碍树
    Perceive --> Perceive: 短暂截图重试
    Perceive --> Failed: 重试耗尽且无降级帧
    Plan --> Validate: 恰好一个动作工具
    Plan --> Complete: complete_task
    Plan --> Route: 工具调用缺失或非法
    Validate --> Execute: 类型化动作合法
    Validate --> Route: 参数或坐标失败
    Execute --> Verify: 执行器返回结果
    Verify --> Route: 后置观察完成
    Route --> Plan: 已验证或可重试失败
    Route --> Reflect: 连续失败或 A-B 循环
    Validate --> Reflect: 阻止重复或重复发送
    Reflect --> Perceive: 固化证据与禁止动作
    Perceive --> Replan: 取得反思新快照
    Replan --> Validate: 实质不同的恢复动作
    Replan --> Reflect: 仍提出相同禁止动作
    Replan --> Adjudicate: 恢复多次被拒绝
    Adjudicate --> TakeOver: 人工可继续
    Adjudicate --> Failed: 接管无意义
    Route --> Perceive: 观察缺失
    Route --> TakeOver: 请求接管
    Route --> Reflect: 动作失败或无进展预算
    Route --> Failed: 协议失败或最大步数安全边界
    Complete --> [*]
    TakeOver --> [*]
    Failed --> [*]
```

核心能力：

- 主屏与 Shizuku 虚拟屏双平台
- 截图 + 无障碍树 + 前台包名混合感知
- `Launch` / `Tap` / `Type` / `Swipe` / `Back` / `Wait` / `Take_over` 强类型动作
- 导航、闹钟、日历、联系人、短信、邮件、相机等系统工具
- 后置视觉、语义树、包名和输入验证
- 子任务级反思、重复动作检测、候选路径重规划和终局裁决
- 虚拟屏统一接管确认、顶部接管通知、离线中文语音

完整说明见 [benchmarks/README.md](benchmarks/README.md)，

## Deploy

真机要求：

- Android 12 / API 31 或更高
- 启用 Umbra 无障碍服务
- 虚拟屏模式需要 Shizuku 已运行并授权

构建并安装：

```powershell
cd android

.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

Benchmark 构建：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File `
  .\tools\benchmark\run-benchmark.ps1 `
  -Suite .\benchmarks\suites\benchtask-smoke.json `
  -BuildAndInstallBenchmark
```

## Environment

模型配置优先在 App 设置中填写。Debug 构建也可以使用 Git 忽略的 `android/local.properties`：

| Variable | Purpose | Example |
|---|---|---|
| `VLM_API_KEY` | Provider API key | user-configured |
| `VLM_BASE_URL` | OpenAI-compatible base URL | `https://api.deepseek.com` |
| `VLM_MODEL` | Multimodal model name | `deepseek-v4-flash-vision-exp` |
| `umbra.apiKey` | Optional local debug key | not committed |

App 会为 Base URL 自动补齐 `/chat/completions`。

## Benchmark

项目提供 52 条真机任务、冒烟集、最终集、随机抽样和人工评分 runner。

快速校验：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File `
  .\tools\benchmark\run-benchmark.ps1 `
  -Suite .\benchmarks\suites\benchtask-smoke.json `
  -ValidateOnly
```

三轮结果见[benchmark-three-round-summary.md](benchmarks/benchmark-three-round-summary.md)。

## Debug

实时查看 Agent 决策与验证：

```powershell
.\tools\monitor-agent.ps1 -VerboseTrace
```

完整设计见 [docs/architecture-v2.md](docs/architecture-v2.md)，发布说明见
[docs/releases/v2.1.0.md](docs/releases/v2.1.0.md)。

## License

[Apache License 2.0](LICENSE)
