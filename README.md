# Umbra phone-agent 2.0.0

Umbra 是一个运行在真实 Android 手机上的任务型 Agent。它可以操作主屏，也可以通过 Shizuku 在独立虚拟屏中工作，让用户继续使用主屏。

2.x 不再依赖 AutoGLM Agent。Android App 使用 Kotlin 本地图状态机编排通用多模态模型，模型通过 OpenAI-compatible Chat Completions 与 function calling 选择动作；动作协议、参数校验、执行、后置验证和熔断均由本地代码控制。

## 2.0 核心能力

- Kotlin `StateGraph`：`Perceive → Plan → Validate → Execute → Verify → Route`。
- 通用 VLM：可配置支持图片输入和 function calling 的 OpenAI-compatible Base URL 与模型。
- 混合感知：压缩截图 + 裁剪后的无障碍语义树 + 前台包名 + 输入焦点。
- 强类型动作：基础视觉动作 `Launch`、`Tap`、`Type`、`Swipe`、`Back`、`Wait`、`Take_over`，以及由 Kotlin 固定定义的低/中风险 Android 系统工具。
- 系统工具优先：导航、闹钟、计时器、日历、联系人、短信/邮件撰写、拨号准备、相机、网页/搜索、系统设置、分享和媒体播放无需再逐步截图点击。
- 安全分级：低风险工具可直接分发；中风险工具只打开预填/确认界面。虚拟屏中的中风险工具会保留现场并等待用户确认接管到主屏。
- 独立完成控制：`complete_task` 只结束图，不属于手机动作。
- 后置验证：按页面稳定度自适应等待，再做视觉变化、语义树、前台包名和输入内容校验。
- 精准点击：优先使用语义匹配的无障碍节点 bounds 中心；视觉兜底截图最长边 2560。
- 确定性启动：明确的已安装应用启动请求由 Kotlin 优先执行 `Launch`，桌面搜索仅作为回退。
- 帧一致性：跟踪截图新鲜度，拒绝使用与最新无障碍树冲突的缓存帧继续规划。
- 子任务级反思：每个中间目标独立累计恢复次数，前 5 次反思可继续尝试，第 6 次才进入终局裁决；完成并切换子任务后清零。全局 8 步无进展只触发反思，不再直接熔断。
- 双平台：主屏无障碍平台与 Shizuku 虚拟屏平台共用同一 Agent 图。
- 虚拟屏预览和静默输入法保留。
- 对话式主界面：任务与执行反馈以聊天消息展示，模型、权限、模式和调试统一收纳在设置页。
- 可确认的虚拟屏接管：仅启动应用的任务完成后，或支付/验证码等触发 `Take_over` 时，等待用户确认并尽量把现有 root task 原状态迁移到主屏。
- Vosk 中文离线语音：主页面与通知栏共享语音入口，识别结果直接进入聊天/任务路由；首次使用下载约 42 MB 官方小模型。
- 常驻控制通知：显示当前 Step、图节点和验证摘要，支持输入命令、语音命令、停止任务、重试，以及接管确认/取消。

完整设计和流程图见 [2.0 架构说明](docs/architecture-v2.md)。

## 目录结构

```text
android/app/src/main/kotlin/com/bluewhale/agent/
  core/
    graph/              Kotlin StateGraph 与条件路由
    ActionValidator.kt  动作前置校验和坐标转换
    ToolCallParser.kt   function-calling 解析
    OpenAiCompatibleVlmClient.kt
  perception/           截图指纹与无障碍树感知
  verification/         独立后置验证
  platform/             主屏/虚拟屏执行平台
    SystemCapabilityExecutor.kt  白名单 Android Intent 构造与分发
  virtualdisplay/       Shizuku 虚拟屏底层（保持隔离）
  input/                内置静默输入法
  service/              生命周期与前台服务
  ui/                   Compose 对话界面、设置与接管确认
  voice/                Vosk 离线中文语音输入与模型管理
agent/                   早期 Python/ADB 验证工具，不参与 Android 2.0 运行
docs/                    架构与工程记录
reference/               本地参考源码（git ignored）
```

## 模型要求

所选模型与接口需要支持：

1. OpenAI-compatible `POST /chat/completions`。
2. 多模态 `image_url` 输入。
3. `tools` / function calling，并能返回 `message.tool_calls`。

默认 Provider 为 DeepSeek，Base URL 是 `https://api.deepseek.com`，模型是 `deepseek-v4-flash-vision-exp`。也可以在 App 的“设置”中替换为其他兼容 Provider。Base URL 可以填写到 `/v1`（App 自动追加 `/chat/completions`），也可以填写完整的 `/chat/completions` 地址。

本机 Debug 构建可在被 Git 忽略的 `android/local.properties` 中配置 `umbra.apiKey`，仅用于个人真机调试。Release 构建会强制把内置 Key 设为空，不会继承该本机配置；用户需在 App 设置中自行填写 Provider Key。任何 APK 发布前仍应执行字符串级敏感信息扫描。

## 真机要求

- Android 12 以上。
- 在系统设置中启用 Umbra 的无障碍服务。
- 虚拟屏模式需要安装、启动并授权 Shizuku。
- 语音输入需要麦克风权限；首次使用需要联网下载 Vosk 中文模型，之后可离线识别。
- Shizuku APK 位于 `third_party/shizuku.apk`。

## 构建与测试

```powershell
cd android
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat assembleDebug
```

APK 输出：

```text
android\app\build\outputs\apk\debug\app-debug.apk
```

## 电脑端实时调试

Agent 内部过程不在手机 UI 中展示。连接手机后，在 PowerShell 运行：

```powershell
.\tools\monitor-agent.ps1
```

监控器会按 Step 彩色展示感知、当前子任务、模型动作、Tap 的归一化/像素坐标与目标节点、Type 文字、页面稳定等待、后置验证和子任务反思计数。保存结构化 JSONL：

```powershell
.\tools\monitor-agent.ps1 -OutputPath .\umbra-agent.jsonl
```

无需手机的显示演示：

```powershell
.\tools\monitor-agent.ps1 -Demo
```

Debug APK 会记录完整任务和 Type 文本，仅用于本机调试；Release 构建会自动脱敏。不要公开分享 Debug 日志。

## 许可证

Umbra phone-agent 采用 [Apache License 2.0](LICENSE) 发布。

## 当前边界

- 系统工具不包含删除联系人/日历、静默发送短信、直接拨号等高风险能力；这些请求仍需用户在系统确认界面完成。
- `Take_over` 会保留虚拟屏并等待用户确认；确认后通过隐藏的 `moveRootTaskToDisplay` 迁移当前可见任务。厂商系统拒绝迁移时不会销毁现场，用户可重试或取消。
- 部分自绘控件不会暴露无障碍树，系统会回退到截图判断。
- `Type` 采用严格验证；目标应用不回显文本且执行器无法自证时会失败并重规划。
- 虚拟屏底层依赖 Android 隐藏 API，不同厂商系统仍需真机回归。

## 免责声明

本项目仅供学习、研究和自动化技术验证。用户应遵守目标应用和平台条款，不得用于批量骚扰、绕过安全策略或违法活动。
