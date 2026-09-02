package com.bluewhale.agent.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.bluewhale.agent.BuildConfig
import com.bluewhale.agent.core.OpenAiCompatibleVlmClient
import com.bluewhale.agent.core.graph.AgentGraph
import com.bluewhale.agent.conversation.ConversationKind
import com.bluewhale.agent.conversation.ConversationRole
import com.bluewhale.agent.conversation.ConversationRoute
import com.bluewhale.agent.conversation.ConversationStore
import com.bluewhale.agent.conversation.OpenAiConversationRouter
import com.bluewhale.agent.conversation.TaskMemoryStore
import com.bluewhale.agent.debug.AgentLogcat
import com.bluewhale.agent.input.SilentImeController
import com.bluewhale.agent.model.AgentRunState
import com.bluewhale.agent.model.PhoneAction
import com.bluewhale.agent.model.DEFAULT_VLM_BASE_URL
import com.bluewhale.agent.model.DEFAULT_VLM_MODEL
import com.bluewhale.agent.model.TargetMode
import com.bluewhale.agent.model.VlmConfig
import com.bluewhale.agent.platform.AccessibilityAgentPlatform
import com.bluewhale.agent.platform.AgentPlatform
import com.bluewhale.agent.platform.AppPackages
import com.bluewhale.agent.platform.DeviceProfile
import com.bluewhale.agent.platform.VirtualDisplayAgentPlatform
import com.bluewhale.agent.ui.MainActivity
import com.bluewhale.agent.voice.VoiceCommandService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

class AgentService : AccessibilityService() {
    companion object {
        const val ACTION_START_TASK = "com.bluewhale.agent.action.START_TASK"
        const val ACTION_STOP_TASK = "com.bluewhale.agent.action.STOP_TASK"
        const val ACTION_NOTIFICATION_COMMAND =
            "com.bluewhale.agent.action.NOTIFICATION_COMMAND"
        const val ACTION_REPEAT_LAST_TASK =
            "com.bluewhale.agent.action.REPEAT_LAST_TASK"
        const val ACTION_APPROVE_TAKEOVER =
            "com.bluewhale.agent.action.APPROVE_TAKEOVER"
        const val ACTION_CANCEL_TAKEOVER =
            "com.bluewhale.agent.action.CANCEL_TAKEOVER"

        const val EXTRA_TASK = "task"
        const val EXTRA_MODE = "mode"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_BASE_URL = "base_url"
        const val EXTRA_MODEL = "model"
        const val EXTRA_MAX_STEPS = "max_steps"
        const val EXTRA_CONVERSATION_CONTEXT = "conversation_context"
        const val EXTRA_BENCHMARK_RUN_ID = "benchmark_run_id"
        const val REMOTE_INPUT_COMMAND = "notification_command"
        const val EXTRA_DIRECT_COMMAND = "direct_notification_command"

        private const val CHANNEL_ID = "bluewhale_agent"
        private const val TAKEOVER_CHANNEL_ID = "bluewhale_agent_takeover"
        private const val NOTIFICATION_ID = 1001

        val state = MutableStateFlow(AgentRunState())
        val preview = MutableStateFlow<Bitmap?>(null)

        fun startTask(
            context: Context,
            task: String,
            mode: TargetMode,
            config: VlmConfig,
            maxSteps: Int = config.maxSteps,
            benchmarkRunId: String? = null,
        ) {
            val conversationContext =
                if (benchmarkRunId.isNullOrBlank()) {
                    buildString {
                        TaskMemoryStore.recentPrompt().takeIf { it.isNotBlank() }?.let {
                            appendLine(it)
                        }
                        appendLine("相关会话上下文：")
                        append(
                            ConversationStore.recent(12).joinToString("\n") { message ->
                                val role =
                                    if (message.role == ConversationRole.USER) "用户" else "Umbra"
                                "$role：${message.text}"
                            },
                        )
                    }
                } else {
                    ""
                }
            val intent =
                Intent(context, AgentService::class.java)
                    .setAction(ACTION_START_TASK)
                    .putExtra(EXTRA_TASK, task)
                    .putExtra(EXTRA_MODE, mode.name)
                    .putExtra(EXTRA_API_KEY, config.apiKey)
                    .putExtra(EXTRA_BASE_URL, config.baseUrl)
                    .putExtra(EXTRA_MODEL, config.model)
                    .putExtra(EXTRA_MAX_STEPS, maxSteps)
                    .putExtra(EXTRA_CONVERSATION_CONTEXT, conversationContext)
                    .putExtra(EXTRA_BENCHMARK_RUN_ID, benchmarkRunId.orEmpty())
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopTask(context: Context) {
            val intent =
                Intent(context, AgentService::class.java)
                    .setAction(ACTION_STOP_TASK)
            context.startService(intent)
        }

        fun approveTakeover(context: Context) {
            context.startService(
                Intent(context, AgentService::class.java).setAction(ACTION_APPROVE_TAKEOVER),
            )
        }

        fun cancelTakeover(context: Context) {
            context.startService(
                Intent(context, AgentService::class.java).setAction(ACTION_CANCEL_TAKEOVER),
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var commandJob: Job? = null
    private var pendingStart: Intent? = null
    private var runGeneration: Long = 0L
    private var pendingTakeoverPlatform: VirtualDisplayAgentPlatform? = null
    private var takeoverRequestActive: Boolean = false

    override fun onCreate() {
        super.onCreate()
        ConversationStore.initialize(this)
        createNotificationChannel()
        TaskMemoryStore.initialize(this)
        publishIdleNotification("Umbra 已就绪，可直接输入命令")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val pending = pendingStart
        pendingStart = null
        if (pending != null) {
            handleStart(pending)
        } else {
            publishIdleNotification("Umbra 已就绪，可直接输入命令")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_TASK -> stopTaskRun()
            ACTION_APPROVE_TAKEOVER -> approveTakeover()
            ACTION_CANCEL_TAKEOVER -> cancelTakeover()
            ACTION_NOTIFICATION_COMMAND -> {
                val command =
                    intent.getStringExtra(EXTRA_DIRECT_COMMAND)
                        ?: RemoteInput.getResultsFromIntent(intent)
                            ?.getCharSequence(REMOTE_INPUT_COMMAND)
                            ?.toString()
                            .orEmpty()
                handleNotificationCommand(command)
            }
            ACTION_REPEAT_LAST_TASK -> {
                val task = TaskMemoryStore.latestTask().orEmpty()
                if (task.isBlank()) {
                    publishIdleNotification("没有可重试的历史任务")
                } else {
                    handleNotificationCommand(task)
                }
            }
            ACTION_START_TASK -> {
                startForeground(NOTIFICATION_ID, buildNotification("Umbra 正在准备任务"))
                if (rootInActiveWindow == null && pendingStart == null) {
                    pendingStart = intent
                } else {
                    handleStart(intent)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        startForeground(NOTIFICATION_ID, buildNotification("Umbra 正在启动 Agent"))
        val previousJob = job
        val runId = ++runGeneration

        val task = intent.getStringExtra(EXTRA_TASK).orEmpty()
        val conversationContext = intent.getStringExtra(EXTRA_CONVERSATION_CONTEXT).orEmpty()
        val benchmarkRunId = intent.getStringExtra(EXTRA_BENCHMARK_RUN_ID).orEmpty()
        val mode =
            runCatching { TargetMode.valueOf(intent.getStringExtra(EXTRA_MODE).orEmpty()) }
                .getOrDefault(TargetMode.MAIN_SCREEN)
        val config =
            VlmConfig(
                apiKey = intent.getStringExtra(EXTRA_API_KEY).orEmpty(),
                baseUrl = intent.getStringExtra(EXTRA_BASE_URL).orEmpty().ifBlank {
                    DEFAULT_VLM_BASE_URL
                },
                model = intent.getStringExtra(EXTRA_MODEL).orEmpty().ifBlank { DEFAULT_VLM_MODEL },
                maxSteps = intent.getIntExtra(EXTRA_MAX_STEPS, 40).coerceIn(1, 200),
            )

        updateRunState(
            AgentRunState(
                running = true,
                task = task,
                mode = mode,
                status = "准备启动",
            ),
        )

        job =
            scope.launch {
                previousJob?.cancelAndJoin()
                if (runId != runGeneration) return@launch
                releasePendingTakeover()
                SilentImeController.restore()
                setPreview(null)
                val finalMessage =
                    try {
                        runTask(task, mode, config, conversationContext, runId, benchmarkRunId)
                    } catch (_: CancellationException) {
                        "已停止"
                    } catch (error: Exception) {
                        if (BuildConfig.DEBUG) {
                            Log.e(AgentLogcat.TAG, "Task crashed before a user-facing result", error)
                        } else {
                            Log.e(
                                AgentLogcat.TAG,
                                "Task crashed before a user-facing result: ${error::class.java.simpleName}",
                            )
                        }
                        userFacingTaskError(error)
                    }
                if (runId != runGeneration) return@launch
                state.value =
                    state.value.copy(
                        running = false,
                        task = task,
                        mode = mode,
                        status = finalMessage,
                    )
                val isError =
                    state.value.phase == com.bluewhale.agent.model.AgentPhase.FAILED ||
                        finalMessage.contains("失败") ||
                        finalMessage.contains("熔断")
                if (benchmarkRunId.isBlank()) {
                    ConversationStore.appendAssistant(
                        finalMessage,
                        if (isError) ConversationKind.ERROR else ConversationKind.TASK,
                    )
                }
                job = null
                stopForeground(android.app.Service.STOP_FOREGROUND_DETACH)
                if (state.value.awaitingTakeover) {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(finalMessage, state.value))
                } else {
                    publishIdleNotification(finalMessage)
                }
            }
    }

    private suspend fun runTask(
        task: String,
        mode: TargetMode,
        config: VlmConfig,
        conversationContext: String,
        runId: Long,
        benchmarkRunId: String,
    ): String {
        val platform: AgentPlatform =
            when (mode) {
                TargetMode.MAIN_SCREEN -> AccessibilityAgentPlatform(this)
                TargetMode.VIRTUAL_DISPLAY -> VirtualDisplayAgentPlatform(this)
            }

            if (!platform.isAvailable()) {
                return when (mode) {
                    TargetMode.MAIN_SCREEN -> "无障碍服务未连接，请先在系统设置中启用 Umbra phone-agent"
                    TargetMode.VIRTUAL_DISPLAY -> "虚拟屏需要 Shizuku 权限，当前尚未就绪"
                }
            }

            return coroutineScope {
                val bootstrapAction =
                    AppPackages.explicitLaunchTarget(packageManager, task)
                        ?.let(PhoneAction::Launch)
                val deviceProfile = DeviceProfile.read(this@AgentService).promptText()
                val previewJob =
                    launch {
                    platform.preview.collect { bitmap ->
                        if (runId == runGeneration) setPreview(bitmap)
                    }
                }
                try {
                    val graph =
                        AgentGraph(
                        platform = platform,
                        model =
                            OpenAiCompatibleVlmClient(
                                config = config,
                                conversationContext = conversationContext,
                                deviceProfile = deviceProfile,
                            ),
                        task = task,
                        maxSteps = config.maxSteps,
                        bootstrapAction = bootstrapAction,
                        maxConsecutiveFailures = config.maxConsecutiveFailures,
                        onState = { next ->
                            if (runId == runGeneration) {
                                val previous = state.value
                                val messages =
                                    listOf(next.status, next.verification)
                                        .filter { it.isNotBlank() }
                                val mergedLogs = previous.logs.toMutableList()
                                messages.forEach { message ->
                                    if (mergedLogs.lastOrNull() != message) mergedLogs += message
                                }
                                updateRunState(
                                    next.copy(
                                        logs = mergedLogs.takeLast(200),
                                    ),
                                )
                            }
                        },
                        onTrace = { event ->
                            AgentLogcat.write(
                                if (benchmarkRunId.isBlank()) {
                                    event
                                } else {
                                    event.copy(
                                        fields =
                                            event.fields +
                                                ("benchmark_run_id" to benchmarkRunId),
                                    )
                                },
                            )
                        },
                    )
                    val result = graph.run()
                    if (
                        result.phase == com.bluewhale.agent.model.AgentPhase.TAKEOVER &&
                        platform is VirtualDisplayAgentPlatform
                    ) {
                        pendingTakeoverPlatform = platform
                        platform.pauseForTakeover()
                    }
                    if (runId == runGeneration && benchmarkRunId.isBlank()) {
                        TaskMemoryStore.record(
                            task = task,
                            mode = mode,
                            phase = result.phase,
                            step = result.step,
                            finalMessage = result.message,
                            packageName = result.packageName,
                        )
                    }
                    result.message
                } finally {
                    previewJob.cancelAndJoin()
                    if (runId == runGeneration) setPreview(null)
                }
            }
    }

    private fun setPreview(bitmap: Bitmap?) {
        val previous = preview.value
        preview.value = bitmap
        if (previous !== bitmap && previous != null) {
            previous.recycle()
        }
    }

    private fun cancelCurrentRun() {
        runGeneration += 1
        SilentImeController.restore()
        job?.cancel()
        setPreview(null)
        job = null
        scope.launch { releasePendingTakeover() }
    }

    private suspend fun releasePendingTakeover() {
        val platform = pendingTakeoverPlatform ?: return
        pendingTakeoverPlatform = null
        runCatching { platform.stop() }
            .onFailure { Log.w(AgentLogcat.TAG, "Failed to release retained takeover display", it) }
        setPreview(null)
    }

    private fun approveTakeover() {
        if (takeoverRequestActive) return
        val platform = pendingTakeoverPlatform
        if (platform == null) {
            publishIdleNotification("没有等待接管的虚拟屏任务")
            return
        }
        takeoverRequestActive = true
        pendingTakeoverPlatform = null
        scope.launch {
            try {
                val result =
                    try {
                        platform.handoffToMainScreen()
                    } catch (error: Exception) {
                        com.bluewhale.agent.model.ActionResult.Failure(
                            error.message ?: "接管迁移失败",
                            error,
                        )
                    }
                val message =
                    when (result) {
                        is com.bluewhale.agent.model.ActionResult.Success -> result.message
                        is com.bluewhale.agent.model.ActionResult.Failure -> result.message
                    }
                if (result is com.bluewhale.agent.model.ActionResult.Success) {
                    ConversationStore.appendAssistant(message, ConversationKind.TASK)
                    updateRunState(
                        state.value.copy(
                            running = false,
                            phase = com.bluewhale.agent.model.AgentPhase.COMPLETE,
                            awaitingTakeover = false,
                            status = message,
                        ),
                    )
                    platform.stop()
                    setPreview(null)
                    stopForeground(android.app.Service.STOP_FOREGROUND_DETACH)
                    publishIdleNotification(message)
                } else {
                    ConversationStore.appendAssistant(message, ConversationKind.ERROR)
                    pendingTakeoverPlatform = platform
                    updateRunState(
                        state.value.copy(
                            running = false,
                            phase = com.bluewhale.agent.model.AgentPhase.TAKEOVER,
                            awaitingTakeover = true,
                            status = "$message；虚拟屏仍保留，可重试或取消",
                        ),
                    )
                }
            } finally {
                takeoverRequestActive = false
            }
        }
    }

    private fun cancelTakeover() {
        if (takeoverRequestActive) return
        if (pendingTakeoverPlatform == null) {
            publishIdleNotification("没有等待接管的虚拟屏任务")
            return
        }
        takeoverRequestActive = true
        scope.launch {
            try {
                releasePendingTakeover()
                val message = "已取消人工接管并关闭虚拟屏任务"
                ConversationStore.appendAssistant(message, ConversationKind.TASK)
                updateRunState(
                    state.value.copy(
                        running = false,
                        phase = com.bluewhale.agent.model.AgentPhase.COMPLETE,
                        awaitingTakeover = false,
                        status = message,
                    ),
                )
                stopForeground(android.app.Service.STOP_FOREGROUND_DETACH)
                publishIdleNotification(message)
            } finally {
                takeoverRequestActive = false
            }
        }
    }
    private fun stopTaskRun() {
        cancelCurrentRun()
        pendingStart = null
        ConversationStore.appendAssistant("已终止任务", ConversationKind.TASK)
        state.value =
            state.value.copy(
                running = false,
                phase = com.bluewhale.agent.model.AgentPhase.COMPLETE,
                awaitingTakeover = false,
                status = "已终止任务",
            )
        stopForeground(android.app.Service.STOP_FOREGROUND_DETACH)
        publishIdleNotification("已终止任务")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        stopTaskRun()
    }

    override fun onDestroy() {
        commandJob?.cancel()
        cancelCurrentRun()
        scope.cancel()
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun handleNotificationCommand(rawCommand: String) {
        val command = rawCommand.trim()
        if (command.isBlank()) {
            publishIdleNotification("请输入命令后再发送")
            return
        }
        if (state.value.running) {
            getSystemService(NotificationManager::class.java)
                .notify(
                    NOTIFICATION_ID,
                    buildNotification("当前任务仍在运行，请先点击“停止任务”", state.value),
                )
            return
        }
        val (mode, config) = savedRuntimeConfig()
        if (config.apiKey.isBlank()) {
            ConversationStore.appendAssistant(
                "通知命令无法执行：请先在 App 设置中填写 API Key。",
                ConversationKind.ERROR,
            )
            publishIdleNotification("请先在 App 设置中填写 API Key")
            return
        }
        commandJob?.cancel()
        commandJob =
            scope.launch {
                ConversationStore.appendUser(command)
                publishIdleNotification("正在理解通知命令…")
                try {
                    when (
                        val route =
                            OpenAiConversationRouter(config).route(
                                input = command,
                                history = ConversationStore.recent(),
                            )
                    ) {
                        is ConversationRoute.Chat -> {
                            ConversationStore.appendAssistant(route.reply)
                            publishIdleNotification(route.reply)
                        }
                        is ConversationRoute.Task ->
                            startTask(
                                context = this@AgentService,
                                task = route.task,
                                mode = mode,
                                config = config,
                            )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (BuildConfig.DEBUG) {
                        Log.e(AgentLogcat.TAG, "Notification command failed", error)
                    } else {
                        Log.e(
                            AgentLogcat.TAG,
                            "Notification command failed: ${error::class.java.simpleName}",
                        )
                    }
                    val message = "通知命令处理失败，请稍后重试。"
                    ConversationStore.appendAssistant(message, ConversationKind.ERROR)
                    publishIdleNotification(message)
                }
            }
    }

    private fun userFacingTaskError(error: Exception): String {
        val detail = error.message.orEmpty()
        return when {
            detail.contains("display from a Context", ignoreCase = true) ||
                detail.contains("Default display is unavailable", ignoreCase = true) ->
                "读取目标屏幕信息失败，请重新选择执行屏幕后重试。"

            detail.contains("HTTP 401", ignoreCase = true) ||
                detail.contains("unauthorized", ignoreCase = true) ->
                "模型认证失败，请检查设置中的 API Key。"

            detail.contains("HTTP 429", ignoreCase = true) ||
                detail.contains("rate limit", ignoreCase = true) ->
                "模型请求过于频繁或额度不足，请稍后重试。"

            detail.contains("timeout", ignoreCase = true) ->
                "任务请求超时，请检查网络后重试。"

            else -> "任务执行失败，请查看电脑端 Agent 日志了解详情。"
        }
    }

    private fun savedRuntimeConfig(): Pair<TargetMode, VlmConfig> {
        val preferences = getSharedPreferences("bluewhale_prefs", Context.MODE_PRIVATE)
        val mode =
            runCatching {
                TargetMode.valueOf(
                    preferences.getString("mode", TargetMode.MAIN_SCREEN.name).orEmpty(),
                )
            }.getOrDefault(TargetMode.MAIN_SCREEN)
        return mode to
            VlmConfig(
                apiKey =
                    preferences.getString("api_key", BuildConfig.DEFAULT_API_KEY)
                        .orEmpty()
                        .trim(),
                baseUrl =
                    preferences.getString("base_url", DEFAULT_VLM_BASE_URL)
                        .orEmpty()
                        .ifBlank { DEFAULT_VLM_BASE_URL },
                model =
                    preferences.getString("model", DEFAULT_VLM_MODEL)
                        .orEmpty()
                        .ifBlank { DEFAULT_VLM_MODEL },
                maxSteps = preferences.getInt("max_steps", 40).coerceIn(1, 200),
            )
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Umbra phone-agent 任务状态",
                NotificationManager.IMPORTANCE_LOW,
            )
        channel.description = "实时显示 Umbra Agent 的任务阶段、动作步数和最近日志"
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val takeoverChannel =
            NotificationChannel(
                TAKEOVER_CHANNEL_ID,
                "Umbra 人工接管请求",
                NotificationManager.IMPORTANCE_HIGH,
            )
        takeoverChannel.description = "任务需要你确认是否从虚拟屏迁移到主屏"
        getSystemService(NotificationManager::class.java).createNotificationChannel(takeoverChannel)
    }

    private fun updateRunState(next: AgentRunState) {
        state.value = next
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(next.status, next))
    }

    private fun publishIdleNotification(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun buildNotification(
        text: String,
        runState: AgentRunState? = null,
    ): Notification {
        val launchIntent =
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val voiceCommandIntent =
            Intent(this, VoiceCommandService::class.java)
                .setAction(VoiceCommandService.ACTION_START)
        val voiceCommandPendingIntent =
            PendingIntent.getForegroundService(
                this,
                1,
                voiceCommandIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val commandIntent =
            Intent(this, AgentService::class.java)
                .setAction(ACTION_NOTIFICATION_COMMAND)
        val commandPendingIntent =
            PendingIntent.getService(
                this,
                2,
                commandIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
        val commandInput =
            RemoteInput.Builder(REMOTE_INPUT_COMMAND)
                .setLabel("输入聊天内容或手机任务")
                .build()
        val commandAction =
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_send,
                "输入命令",
                commandPendingIntent,
            )
                .addRemoteInput(commandInput)
                .setAllowGeneratedReplies(false)
                .build()
        val stopPendingIntent =
            PendingIntent.getService(
                this,
                3,
                Intent(this, AgentService::class.java).setAction(ACTION_STOP_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val repeatPendingIntent =
            PendingIntent.getService(
                this,
                4,
                Intent(this, AgentService::class.java).setAction(ACTION_REPEAT_LAST_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val approveTakeoverPendingIntent =
            PendingIntent.getService(
                this,
                5,
                Intent(this, AgentService::class.java).setAction(ACTION_APPROVE_TAKEOVER),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val cancelTakeoverPendingIntent =
            PendingIntent.getService(
                this,
                6,
                Intent(this, AgentService::class.java).setAction(ACTION_CANCEL_TAKEOVER),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val compactText =
            when {
                runState?.awaitingTakeover == true -> "任务需要人工接管"
                runState?.running == true ->
                    "Step ${runState.step} · ${runState.phase.label}"
                else -> text
            }
        val detailText =
            if (runState?.running == true) {
                buildString {
                    appendLine("任务：${runState.task}")
                    appendLine("Step ${runState.step} · ${runState.phase.label}")
                    appendLine(runState.status)
                    runState.verification.takeIf(String::isNotBlank)?.let(::append)
                }.trim()
            } else {
                text
            }
        val awaitingTakeover = runState?.awaitingTakeover == true
        val notificationChannelId =
            if (awaitingTakeover) TAKEOVER_CHANNEL_ID else CHANNEL_ID
        val builder =
            NotificationCompat.Builder(this, notificationChannelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Umbra phone-agent")
            .setContentText(compactText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detailText))
            .setSubText(runState?.mode?.label ?: "点击输入命令")
            .setContentIntent(contentIntent)
            .setOngoing(!awaitingTakeover)
            .setOnlyAlertOnce(!awaitingTakeover)
            .setSilent(!awaitingTakeover)
            .setAutoCancel(awaitingTakeover)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(contentIntent, awaitingTakeover)
            .setPriority(
                if (awaitingTakeover) {
                    NotificationCompat.PRIORITY_HIGH
                } else {
                    NotificationCompat.PRIORITY_DEFAULT
                },
            )
            .setCategory(
                if (awaitingTakeover) {
                    NotificationCompat.CATEGORY_ALARM
                } else {
                    NotificationCompat.CATEGORY_PROGRESS
                },
            )
            .addAction(commandAction)

        if (runState?.awaitingTakeover == true) {
            builder
                .addAction(
                    android.R.drawable.ic_menu_view,
                    "接管到主屏",
                    approveTakeoverPendingIntent,
                )
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "取消接管",
                    cancelTakeoverPendingIntent,
                )
        } else if (runState?.running == true) {
            builder.addAction(
                android.R.drawable.ic_media_pause,
                "停止任务",
                stopPendingIntent,
            )
        } else if (TaskMemoryStore.latestTask() != null) {
            builder.addAction(
                android.R.drawable.ic_popup_sync,
                "重试上次",
                repeatPendingIntent,
            )
        }
        builder.addAction(
                android.R.drawable.ic_btn_speak_now,
                "语音指令",
                voiceCommandPendingIntent,
            )

        return builder.build()
    }
}
