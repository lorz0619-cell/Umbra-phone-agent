# Umbra phone-agent Roadmap

## 0. 产品目标

Bluewhale 是一个运行在真实 Android 手机上的任务型 Agent。用户用自然语言下达任务，并选择任务在哪个屏幕上执行：

- `主屏模式`：Agent 直接操作用户当前使用的屏幕，适合用户明确允许接管屏幕的任务。
- `虚拟屏模式`：Agent 在独立虚拟屏上工作，用户主屏继续正常使用。

第一阶段不追求自研底层控制，而是复用 AutoGLM 成熟的视觉理解与动作规划能力。Bluewhale 第一阶段负责把 AutoGLM 的能力跑在真实手机 App 中，并补齐“虚拟屏隔离执行”这个核心产品能力。

## 1. 第一阶段，可用版本

目标版本：`v1.0.0`

### 1.1 验收标准

- 手机安装 Bluewhale APK 后，不需要连接 PC，也不需要开发者 ADB 调试连接。
- 用户能在 App 内配置 AutoGLM 的 API Key、Base URL 和模型名。
- 用户输入自然语言任务时，可以选择 `主屏模式` 或 `虚拟屏模式`。
- App 启动 Agent 后：
  - 主屏模式通过 Android 无障碍服务感知和操作屏幕。
  - 虚拟屏模式通过 Shizuku 创建独立虚拟屏，执行动作时不改变用户主屏。
- Agent 可以循环执行：截图或读取界面、调用 AutoGLM、解析动作、执行动作。
- 支持常用动作：`Launch`、`Tap`、`Type`、`Swipe`、`Back`、`Home`、`Wait`、`finish`。
- 提供明确的运行状态、停止按钮和简单日志。任务失败时不静默，而是给出可读原因。
- 敏感动作可扩展为暂停或人工确认，第一阶段至少保留 `finish`、停止和异常中断路径。
- 仓库能生成签名 release APK，并通过 `adb install` 或下载安装到 Android 12 以上真机。

### 1.2 技术决策

- 不再把 PC 端 `scrcpy.exe` 作为手机 App 的运行依赖。
- `scrcpy` 继续作为开发调试、真机验证和隔离回归工具。
- 虚拟屏优先采用 Shizuku 调用系统隐藏接口：
  - `IDisplayManager.createVirtualDisplay`
  - `IInputManager.injectInputEvent`
  - `ActivityTaskManager` 相关接口
- 主屏模式优先采用 `AccessibilityService`：
  - `rootInActiveWindow` 和 `windows` 获取界面信息
  - `dispatchGesture` 注入手势
  - `takeScreenshot` 截图
- AutoGLM 通过 OpenAI-compatible `/chat/completions` 接口调用。
- 第一阶段保留 Python 原型作为算法、动作解析和回归脚本的参考实现。

### 1.3 里程碑

- `1.0.0-app-foundation`：Android 工程、App UI、AutoGLM 客户端、Agent 循环、主屏模式。
- `1.0.0-virtual-display`：Shizuku 虚拟屏创建、输入注入、截图和主屏隔离验证。
- `1.0.0-release`：权限引导、配置持久化、签名 release、安装文档和基础隐私说明。

## 2. 第二阶段，体验优化

- 语音识别输入和语音反馈。
- 悬浮窗、灵动岛式任务胶囊或可展开任务面板。
- 更细粒度权限控制、应用白名单和敏感操作确认。
- 任务历史、失败重试、断点恢复和运行日志查看。
- 主屏/虚拟屏切换的运行时提示和更稳定的 IME 处理。

## 3. 第三阶段，自主控制

- 不再依赖 AutoGLM 作为唯一动作规划器。
- 拆出独立的 `Perception`、`Planning`、`Action`、`Memory` 层。
- 建立可替换模型接口，支持 AutoGLM、通用 VLM 和未来本地模型。
- 逐步自研或替换底层 Android 控制路径，减少对单个上游项目的依赖。
- 引入更完整的任务评测集和回归测试，保证替换过程中行为不退化。

## 4. 当前版本状态

当前 Python 原型已完成：

- ADB 物理屏和虚拟屏动作执行。
- AutoGLM 截图分析和动作解析。
- scrcpy 虚拟屏会话。
- 主屏隔离回归检查。
- Chrome CDP 虚拟屏浏览器控制。

当前 Android App 已具备主屏无障碍执行和初版 Shizuku 虚拟屏执行。下一步是真机回归、动作稳定性优化和 release APK 流程。
