# Umbra phone-agent

Umbra 是一个运行在真实 Android 手机上的任务型 Agent。它既可以操作主屏，也可以通过 Shizuku 在独立虚拟屏中执行任务，让用户继续使用主屏。

Android App 使用 Kotlin StateGraph 编排通用 OpenAI-compatible 多模态模型。动作规划、参数校验、执行、后置验证、反思与熔断都在本地完成。

## Architecture

### Project architecture

```mermaid
%%{init: {"theme":"base","themeVariables":{"primaryColor":"#ffffff","primaryTextColor":"#000000","primaryBorderColor":"#000000","lineColor":"#000000","secondaryColor":"#ffffff","tertiaryColor":"#ffffff","edgeLabelBackground":"#ffffff","clusterBkg":"#ffffff","clusterBorder":"#000000"},"flowchart":{"curve":"stepAfter","nodeSpacing":54,"rankSpacing":58,"htmlLabels":true}}}%%
flowchart TB
    subgraph INTERACTION["交互层"]
        direction LR
        UI["Compose 对话主界面"] ~~~ NV["通知栏与语音入口"] ~~~ BM["ADB 日志与 Benchmark"]
    end

    subgraph APPLICATION["应用编排层"]
        direction LR
        CR["Conversation Router"] ~~~ AS["AgentService 生命周期"] ~~~ OM["会话记忆与可观测性"]
    end

    subgraph CORE["Agent 核心层"]
        direction LR
        PE["多模态感知引擎"] --> SG["Kotlin StateGraph"] --> VR["验证与反思引擎"]
    end

    subgraph EXECUTION["能力执行层"]
        direction LR
        MP["主屏执行平台"] ~~~ VP["虚拟屏执行平台"] ~~~ ST["系统能力工具"]
    end

    subgraph ANDROID["Android 集成层"]
        direction LR
        AX["无障碍与截图"] ~~~ SZ["Shizuku 与隐藏 API"] ~~~ II["Intent 与静默输入"]
    end

    UI --> CR
    NV --> AS
    BM --> OM
    CR --> AS
    AS --> OM
    AS --> SG
    OM --> SG
    VR --> SG
    SG --> MP
    SG --> VP
    SG --> ST
    PE --> AX
    MP --> AX
    VP --> SZ
    ST --> II

    classDef box fill:#ffffff,stroke:#000000,color:#000000,stroke-width:1.5px;
    class UI,NV,BM,CR,AS,OM,PE,SG,VR,MP,VP,ST,AX,SZ,II box;
    style INTERACTION fill:#ffffff,stroke:#000000,color:#000000,stroke-width:1px
    style APPLICATION fill:#ffffff,stroke:#000000,color:#000000,stroke-width:1px
    style CORE fill:#ffffff,stroke:#000000,color:#000000,stroke-width:1px
    style EXECUTION fill:#ffffff,stroke:#000000,color:#000000,stroke-width:1px
    style ANDROID fill:#ffffff,stroke:#000000,color:#000000,stroke-width:1px
    linkStyle default stroke:#000000,stroke-width:1.5px,color:#000000;
```

### Agent architecture

```mermaid
%%{init: {"theme":"base","themeVariables":{"primaryColor":"#ffffff","primaryTextColor":"#000000","primaryBorderColor":"#000000","lineColor":"#000000","secondaryColor":"#ffffff","tertiaryColor":"#ffffff","edgeLabelBackground":"#ffffff","clusterBkg":"#ffffff","clusterBorder":"#000000"},"flowchart":{"curve":"stepAfter","nodeSpacing":46,"rankSpacing":62,"htmlLabels":true}}}%%
flowchart TB
    subgraph MAIN["主执行路径"]
        direction LR
        P["Perceive<br/>截图 + 无障碍树"] --> PL["Plan<br/>结构化动作"] --> VA["Validate<br/>动作前置校验"] --> EX["Execute<br/>平台执行"] --> VE["Verify<br/>结果与进度校验"] --> RO["Route<br/>状态路由"]
    end

    subgraph RECOVERY["反思恢复路径"]
        direction LR
        TD["触发检测<br/>失败 · 重复 · 循环"] --> RF["Reflect<br/>子任务反思回合"] --> FP["Fresh Perceive<br/>刷新现场证据"] --> RP["Recovery Plan<br/>生成候选策略"] --> NG["Novelty Gate<br/>差异性校验"] ~~~ TA["Terminal Judge<br/>终止裁决"]
    end

    subgraph TERMINAL["终态"]
        direction LR
        CO["Complete<br/>返回任务总结"] ~~~ TO["Take over<br/>请求用户接管"] ~~~ FA["Failed<br/>返回可诊断错误"]
    end

    RO -->|继续推进| P
    RO -->|失败、重复或循环| TD
    RO -->|目标完成| CO
    RO -->|需要人工操作| TO
    NG -->|候选有效| VA
    NG -->|候选重复| RF
    RF -->|反思预算耗尽| TA
    TA -->|任务已经完成| CO
    TA -->|用户可以处理| TO
    TA -->|不可恢复| FA

    classDef box fill:#ffffff,stroke:#000000,color:#000000,stroke-width:1.5px;
    class P,PL,VA,EX,VE,RO,TD,RF,FP,RP,NG,TA,CO,TO,FA box;
    style MAIN fill:#ffffff,stroke:#000000,color:#000000,stroke-width:1px
    style RECOVERY fill:#ffffff,stroke:#000000,color:#000000,stroke-width:1px
    style TERMINAL fill:#ffffff,stroke:#000000,color:#000000,stroke-width:1px
    linkStyle default stroke:#000000,stroke-width:1.5px,color:#000000;
```

核心能力：

- 主屏与 Shizuku 虚拟屏双平台
- 截图 + 无障碍树 + 前台包名混合感知
- `Launch` / `Tap` / `Type` / `Swipe` / `Back` / `Wait` / `Take_over` 强类型动作
- 导航、闹钟、日历、联系人、短信、邮件、相机等系统工具
- 后置视觉、语义树、包名和输入验证
- 子任务级反思、重复动作检测、候选路径重规划和终局裁决
- 虚拟屏统一接管确认、顶部接管通知、离线中文语音

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

完整说明见 [benchmarks/README.md](benchmarks/README.md)，三轮结果见
[benchmark-three-round-summary.md](benchmarks/benchmark-three-round-summary.md)。

## Debug

实时查看 Agent 决策与验证：

```powershell
.\tools\monitor-agent.ps1 -VerboseTrace
```

完整设计见 [docs/architecture-v2.md](docs/architecture-v2.md)，发布说明见
[docs/releases/v2.1.0.md](docs/releases/v2.1.0.md)。

## License

[Apache License 2.0](LICENSE)
