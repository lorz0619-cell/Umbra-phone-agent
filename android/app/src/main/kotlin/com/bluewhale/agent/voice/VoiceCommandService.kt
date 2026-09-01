package com.bluewhale.agent.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bluewhale.agent.service.AgentService
import com.bluewhale.agent.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** User-initiated microphone FGS used by the notification voice action. */
class VoiceCommandService : Service() {
    companion object {
        const val ACTION_START = "com.bluewhale.agent.action.START_VOICE_COMMAND"
        const val ACTION_STOP = "com.bluewhale.agent.action.STOP_VOICE_COMMAND"
        private const val CHANNEL_ID = "umbra_voice_command"
        private const val NOTIFICATION_ID = 1002
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: VoskVoiceCommandController
    private var recognitionJob: Job? = null
    private var delivered = false

    override fun onCreate() {
        super.onCreate()
        controller = VoskVoiceCommandController(this)
        createChannel()
        scope.launch {
            controller.state.collectLatest(::onVoiceState)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            controller.cancel()
            finishService()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("正在准备离线语音输入…"))
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_VOICE_COMMAND)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            finishService()
            return START_NOT_STICKY
        }
        delivered = false
        recognitionJob?.cancel()
        recognitionJob = scope.launch { controller.prepareAndListen() }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        recognitionJob?.cancel()
        controller.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun onVoiceState(state: VoiceCommandState) {
        when (state) {
            is VoiceCommandState.Preparing -> {
                val suffix = state.progress?.let { " $it%" }.orEmpty()
                notify(state.message + suffix)
            }
            is VoiceCommandState.Listening ->
                notify(
                    state.text.takeIf(String::isNotBlank)
                        ?.let { "已听到：$it" }
                        ?: "正在聆听，请说出聊天内容或手机任务…",
                )
            is VoiceCommandState.Result -> {
                if (!delivered) {
                    delivered = true
                    startService(
                        Intent(this, AgentService::class.java)
                            .setAction(AgentService.ACTION_NOTIFICATION_COMMAND)
                            .putExtra(AgentService.EXTRA_DIRECT_COMMAND, state.text),
                    )
                }
                finishService()
            }
            is VoiceCommandState.Error -> {
                notify(state.message)
                finishService(delayNotificationRemoval = true)
            }
            VoiceCommandState.Idle -> Unit
        }
    }

    private fun createChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Umbra 语音命令",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示离线语音命令的下载、收音和识别状态"
            }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val stopIntent =
            PendingIntent.getService(
                this,
                0,
                Intent(this, VoiceCommandService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Umbra 离线语音命令")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause, "停止收音", stopIntent)
            .build()
    }

    private fun finishService(delayNotificationRemoval: Boolean = false) {
        controller.cancel()
        stopForeground(
            if (delayNotificationRemoval) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE,
        )
        if (delayNotificationRemoval) {
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
        stopSelf()
    }
}
