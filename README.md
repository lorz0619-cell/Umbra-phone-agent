# Umbra phone-agent

Umbra phone-agent 是一个运行在真实 Android 手机上的任务型 Agent。

用户可以用自然语言下达任务，并选择任务执行位置：

- `主屏模式`：Agent 通过无障碍服务直接操作用户当前屏幕。
- `虚拟屏模式`：Agent 在独立虚拟屏中工作，尽量不干扰用户主屏。

当前版本主要依赖 AutoGLM 进行视觉理解和动作规划，Android App 负责截图、解析动作、执行动作，以及虚拟屏隔离。

## 功能

- 配置 AutoGLM API Key、Base URL、模型和最大步数。
- 主屏任务执行。
- Shizuku 虚拟屏任务执行。
- 支持动作：`Launch`、`Tap`、`Type`、`Swipe`、`Back`、`Home`、`Enter`、`Wait`、`finish`。
- 虚拟屏预览。
- 基本运行状态和日志。
- 内置静默输入法，减少主屏输入法弹出。
- Shizuku APK 已打包在 `third_party/shizuku.apk`，方便真机安装依赖。

## 目录结构

```text
android/                 Android App
  app/src/main/kotlin/   主程序
  app/src/main/res/      资源文件
agent/                   Python 原型和早期验证脚本
docs/                    工程日志
third_party/             第三方依赖，如 Shizuku APK
README.md
ROADMAP.md
```

Android 主要模块：

```text
ui/              配置界面
service/         AgentService
core/            AgentLoop / AutoGlmClient / ActionParser
platform/        主屏和虚拟屏平台实现
virtualdisplay/  Shizuku 虚拟屏客户端
input/           内置静默输入法
model/           数据模型
```

## 真机要求

- Android 12 以上。
- 安装并启动 Shizuku。
- 在系统设置中启用 Umbra phone-agent 的无障碍服务。
- 在 App 内授予 Shizuku 权限。

Shizuku APK 路径：

```text
third_party/shizuku.apk
```

## 构建

```powershell
cd X:\bluewhale\android
.\gradlew.bat assembleDebug
```

APK 输出路径：

```text
android\app\build\outputs\apk\debug\app-debug.apk
```

## 当前残留问题

- 静默输入法下，部分应用的无障碍树为空，导致无法直接通过节点写入文字。
- 输入类 Tap 与普通 Tap 的成功判断仍需继续优化。
- 部分消息发送场景可能重复点击发送按钮。
- AutoGLM 对“组织语言、礼貌得体”等写作要求理解不稳定。
- 虚拟屏在部分自定义输入框上的输入兼容性尚未完全覆盖。

## 免责声明

本项目仅供学习、研究和自动化技术验证使用。

- 用户应遵守相关应用和平台的使用条款。
- 使用本软件执行自动化操作可能违反某些服务条款，请自行判断和承担风险。
- 请勿使用本项目进行批量骚扰、绕过安全策略或其他非法用途。
- 作者不对因使用本项目造成的任何损失或后果负责。

## License

当前仓库尚未添加正式 License。请根据实际需求补充后再公开发布。