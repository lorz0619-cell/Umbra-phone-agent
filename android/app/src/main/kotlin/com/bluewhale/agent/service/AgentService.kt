package com.bluewhale.agent.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bluewhale.agent.core.AgentLoop
import com.bluewhale.agent.core.AutoGlmClient
import com.bluewhale.agent.input.SilentImeController
import com.bluewhale.agent.model.AgentRunState
import com.bluewhale.agent.model.AutoGlmConfig
import com.bluewhale.agent.model.TargetMode
import com.bluewhale.agent.platform.AccessibilityAgentPlatform
import com.bluewhale.agent.platform.AgentPlatform
import com.bluewhale.agent.platform.VirtualDisplayAgentPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

class AgentService : AccessibilityService() {
    companion object {
        const val ACTION_START_TASK = "com.bluewhale.agent.action.START_TASK"
        const val ACTION_STOP_TASK = "com.bluewhale.agent.action.STOP_TASK"

        const val EXTRA_TASK = "task"
        const val EXTRA_MODE = "mode"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_BASE_URL = "base_url"
        const val EXTRA_MODEL = "model"
        const val EXTRA_MAX_STEPS = "max_steps"

        private const val CHANNEL_ID = "bluewhale_agent"
        private const val NOTIFICATION_ID = 1001

        val state = MutableStateFlow(AgentRunState())
        val preview = MutableStateFlow<Bitmap?>(null)

        fun startTask(
            context: Context,
            task: String,
            mode: TargetMode,
            config: AutoGlmConfig,
            maxSteps: Int = config.maxSteps,
        ) {
            val intent =
                Intent(context, AgentService::class.java)
                    .setAction(ACTION_START_TASK)
                    .putExtra(EXTRA_TASK, task)
                    .putExtra(EXTRA_MODE, mode.name)
                    .putExtra(EXTRA_API_KEY, config.apiKey)
                    .putExtra(EXTRA_BASE_URL, config.baseUrl)
                    .putExtra(EXTRA_MODEL, config.model)
                    .putExtra(EXTRA_MAX_STEPS, maxSteps)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopTask(context: Context) {
            val intent =
                Intent(context, AgentService::class.java)
                    .setAction(ACTION_STOP_TASK)
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var pendingStart: Intent? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        pendingStart?.let { handleStart(it) }
        pendingStart = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_TASK) {
            stopTaskRun()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START_TASK) {
            startForeground(NOTIFICATION_ID, buildNotification("Bluewhale 正在准备"))
            if (rootInActiveWindow == null && pendingStart == null) {
                pendingStart = intent
            } else {
                handleStart(intent)
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        startForeground(NOTIFICATION_ID, buildNotification("Bluewhale 正在运行"))
        cancelCurrentRun()

        val task = intent.getStringExtra(EXTRA_TASK).orEmpty()
        val mode =
            runCatching { TargetMode.valueOf(intent.getStringExtra(EXTRA_MODE).orEmpty()) }
                .getOrDefault(TargetMode.MAIN_SCREEN)
        val config =
            AutoGlmConfig(
                apiKey = intent.getStringExtra(EXTRA_API_KEY).orEmpty(),
                baseUrl = intent.getStringExtra(EXTRA_BASE_URL).orEmpty().ifBlank {
                    "https://open.bigmodel.cn/api/paas/v4"
                },
                model = intent.getStringExtra(EXTRA_MODEL).orEmpty().ifBlank { "autoglm-phone" },
                maxSteps = intent.getIntExtra(EXTRA_MAX_STEPS, 40).coerceIn(1, 200),
            )

        state.value =
            AgentRunState(
                running = true,
                task = task,
                mode = mode,
                status = "准备启动",
            )

        job =
            scope.launch {
                val finalMessage =
                    try {
                        runTask(task, mode, config)
                    } catch (_: CancellationException) {
                        "已停止"
                    } catch (error: Exception) {
                        error.message ?: "任务失败"
                    }
                state.value =
                    AgentRunState(
                        running = false,
                        task = task,
                        mode = mode,
                        status = finalMessage,
                    )
                stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
            }
    }

    private suspend fun runTask(
        task: String,
        mode: TargetMode,
        config: AutoGlmConfig,
    ): String {
        val platform: AgentPlatform =
            when (mode) {
                TargetMode.MAIN_SCREEN -> AccessibilityAgentPlatform(this)
                TargetMode.VIRTUAL_DISPLAY -> VirtualDisplayAgentPlatform(this)
            }

            if (!platform.isAvailable()) {
                return when (mode) {
                    TargetMode.MAIN_SCREEN -> "无障碍服务未连接，请先在系统设置中启用 Bluewhale"
                    TargetMode.VIRTUAL_DISPLAY -> "虚拟屏需要 Shizuku 权限，当前尚未就绪"
                }
            }

            var result: String? = null
            coroutineScope {
                launch {
                    platform.preview.collect { bitmap -> setPreview(bitmap) }
                }

                val loop =
                    AgentLoop(
                        platform = platform,
                        client = AutoGlmClient(config),
                        task = task,
                        maxSteps = config.maxSteps,
                        onState = { next ->
                            val previous = state.value
                            state.value =
                                next.copy(
                                    logs =
                                        if (next.status.isBlank() || next.status == previous.status) {
                                            previous.logs
                                        } else {
                                            previous.logs + next.status
                                        },
                                )
                        },
                    )
                result = loop.run()
            }
            return result ?: "任务结束"
    }

    private fun setPreview(bitmap: Bitmap?) {
        val previous = preview.value
        preview.value = bitmap
        if (previous !== bitmap && previous != null) {
            previous.recycle()
        }
    }

    private fun cancelCurrentRun() {
        SilentImeController.restore()
        job?.cancel()
        job = null
    }
    private fun stopTaskRun() {
        cancelCurrentRun()
        state.value = state.value.copy(running = false, status = "已停止")
        stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        stopTaskRun()
    }

    override fun onDestroy() {
        cancelCurrentRun()
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Bluewhale Agent",
                NotificationManager.IMPORTANCE_LOW,
            )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Bluewhale Agent")
            .setContentText(text)
            .setOngoing(true)
            .build()
}
