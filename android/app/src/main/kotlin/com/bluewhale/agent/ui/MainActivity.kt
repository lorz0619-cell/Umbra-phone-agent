package com.bluewhale.agent.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.bluewhale.agent.BuildConfig
import com.bluewhale.agent.conversation.ConversationKind
import com.bluewhale.agent.conversation.ConversationMessage
import com.bluewhale.agent.conversation.ConversationRole
import com.bluewhale.agent.conversation.ConversationRoute
import com.bluewhale.agent.conversation.ConversationStore
import com.bluewhale.agent.conversation.OpenAiConversationRouter
import com.bluewhale.agent.model.AgentRunState
import com.bluewhale.agent.model.DEFAULT_VLM_BASE_URL
import com.bluewhale.agent.model.DEFAULT_VLM_MODEL
import com.bluewhale.agent.model.TargetMode
import com.bluewhale.agent.model.VlmConfig
import com.bluewhale.agent.service.AgentService
import com.bluewhale.agent.voice.VoiceCommandState
import com.bluewhale.agent.voice.VoskVoiceCommandController
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

private const val PROVIDER_DEFAULTS_VERSION = 1

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_VOICE_COMMAND = "com.bluewhale.agent.action.VOICE_COMMAND"
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private var voiceRequestNonce by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        seedProviderDefaults()
        ConversationStore.initialize(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    UmbraPhoneAgentApp(
                        context = this,
                        voiceRequestNonce = voiceRequestNonce,
                        onOpenAccessibilitySettings = ::openAccessibilitySettings,
                        onRequestShizukuPermission = ::requestShizukuPermission,
                    )
                }
            }
        }
        handleVoiceCommandIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleVoiceCommandIntent(intent)
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun requestShizukuPermission() {
        if (Shizuku.pingBinder()) {
            Shizuku.requestPermission(2001)
        } else {
            Toast.makeText(this, "请先安装并启动 Shizuku", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleVoiceCommandIntent(intent: Intent?) {
        if (intent?.action == ACTION_VOICE_COMMAND) {
            voiceRequestNonce += 1
            intent.action = null
        }
    }

    private fun seedProviderDefaults() {
        val preferences = getSharedPreferences("bluewhale_prefs", Context.MODE_PRIVATE)
        if (preferences.getInt("provider_defaults_version", 0) >= PROVIDER_DEFAULTS_VERSION) {
            return
        }
        preferences
            .edit()
            .putString("api_key", BuildConfig.DEFAULT_API_KEY)
            .putString("base_url", DEFAULT_VLM_BASE_URL)
            .putString("model", DEFAULT_VLM_MODEL)
            .putInt("provider_defaults_version", PROVIDER_DEFAULTS_VERSION)
            .apply()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UmbraPhoneAgentApp(
    context: Context,
    voiceRequestNonce: Int,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
) {
    val preferences = context.getSharedPreferences("bluewhale_prefs", Context.MODE_PRIVATE)
    var apiKey by rememberSaveable {
        mutableStateOf(preferences.getString("api_key", "").orEmpty())
    }
    var baseUrl by rememberSaveable {
        mutableStateOf(
            preferences
                .getString("base_url", DEFAULT_VLM_BASE_URL)
                .orEmpty(),
        )
    }
    var model by rememberSaveable {
        mutableStateOf(preferences.getString("model", DEFAULT_VLM_MODEL).orEmpty())
    }
    var maxSteps by rememberSaveable {
        mutableStateOf(preferences.getInt("max_steps", 40).toString())
    }
    var modeName by rememberSaveable {
        mutableStateOf(preferences.getString("mode", TargetMode.MAIN_SCREEN.name).orEmpty())
    }
    var taskDraft by rememberSaveable { mutableStateOf("") }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var routing by rememberSaveable { mutableStateOf(false) }

    val runState by AgentService.state.collectAsStateWithLifecycle()
    val preview by AgentService.preview.collectAsStateWithLifecycle()
    val messages by ConversationStore.messages.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val mode =
        runCatching { TargetMode.valueOf(modeName) }.getOrDefault(TargetMode.MAIN_SCREEN)
    val voiceController = remember { VoskVoiceCommandController(context) }
    val voiceState by voiceController.state.collectAsStateWithLifecycle()
    var showVoiceDialog by rememberSaveable { mutableStateOf(false) }

    val submitMessage: (String) -> Unit = { rawInput ->
        val cleanTask = rawInput.trim()
        when {
            cleanTask.isBlank() -> Unit
            routing || runState.running ->
                Toast.makeText(context, "当前任务仍在处理，请先停止或等待完成", Toast.LENGTH_SHORT).show()
            apiKey.isBlank() -> {
                Toast.makeText(context, "请先在设置中填写 VLM API Key", Toast.LENGTH_SHORT).show()
                showSettings = true
            }
            else -> {
                preferences
                    .edit()
                    .putString("api_key", apiKey)
                    .putString("base_url", baseUrl)
                    .putString("model", model)
                    .putString("mode", mode.name)
                    .putInt("max_steps", maxSteps.toIntOrNull() ?: 40)
                    .apply()
                taskDraft = ""
                val config =
                    VlmConfig(
                        apiKey = apiKey.trim(),
                        baseUrl = baseUrl.trim(),
                        model = model.trim(),
                        maxSteps = maxSteps.toIntOrNull() ?: 40,
                    )
                ConversationStore.appendUser(cleanTask)
                routing = true
                coroutineScope.launch {
                    try {
                        when (
                            val route =
                                OpenAiConversationRouter(config).route(
                                    input = cleanTask,
                                    history = ConversationStore.recent(),
                                )
                        ) {
                            is ConversationRoute.Chat ->
                                ConversationStore.appendAssistant(route.reply)
                            is ConversationRoute.Task ->
                                AgentService.startTask(
                                    context = context,
                                    task = route.task,
                                    mode = mode,
                                    config = config,
                                )
                        }
                    } catch (error: Exception) {
                        ConversationStore.appendAssistant(
                            error.message ?: "暂时无法处理这条消息",
                            ConversationKind.ERROR,
                        )
                    } finally {
                        routing = false
                    }
                }
            }
        }
    }
    val audioPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                showVoiceDialog = true
                coroutineScope.launch { voiceController.prepareAndListen() }
            } else {
                Toast.makeText(context, "需要麦克风权限才能使用离线语音输入", Toast.LENGTH_LONG).show()
            }
        }
    val beginVoiceInput: () -> Unit = {
        if (runState.running || routing) {
            Toast.makeText(context, "当前任务仍在处理，请先停止或等待完成", Toast.LENGTH_SHORT).show()
        } else if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            showVoiceDialog = true
            coroutineScope.launch { voiceController.prepareAndListen() }
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    DisposableEffect(voiceController) {
        onDispose { voiceController.close() }
    }
    LaunchedEffect(voiceRequestNonce) {
        if (voiceRequestNonce > 0) beginVoiceInput()
    }
    LaunchedEffect(voiceState) {
        val result = voiceState as? VoiceCommandState.Result ?: return@LaunchedEffect
        showVoiceDialog = false
        taskDraft = result.text
        voiceController.cancel()
        submitMessage(result.text)
    }

    BackHandler(enabled = showSettings) {
        showSettings = false
    }

    if (showSettings) {
        SettingsScreen(
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = model,
            maxSteps = maxSteps,
            mode = mode,
            preview = preview,
            onBack = { showSettings = false },
            onApiKeyChange = {
                apiKey = it
                preferences.edit().putString("api_key", it).apply()
            },
            onBaseUrlChange = {
                baseUrl = it
                preferences.edit().putString("base_url", it).apply()
            },
            onModelChange = {
                model = it
                preferences.edit().putString("model", it).apply()
            },
            onMaxStepsChange = { raw ->
                maxSteps = raw.filter(Char::isDigit)
                preferences
                    .edit()
                    .putInt("max_steps", maxSteps.toIntOrNull() ?: 40)
                    .apply()
            },
            onModeChange = {
                modeName = it.name
                preferences.edit().putString("mode", it.name).apply()
            },
            onOpenAccessibilitySettings = onOpenAccessibilitySettings,
            onRequestShizukuPermission = onRequestShizukuPermission,
            onClearConversation = ConversationStore::clear,
        )
        return
    }

    if (showVoiceDialog) {
        VoiceCommandDialog(
            state = voiceState,
            onFinish = voiceController::finishListening,
            onCancel = {
                voiceController.cancel()
                showVoiceDialog = false
            },
            onRetry = { coroutineScope.launch { voiceController.prepareAndListen() } },
        )
    }

    ChatScreen(
        taskDraft = taskDraft,
        mode = mode,
        runState = runState,
        messages = messages,
        routing = routing,
        onTaskDraftChange = { taskDraft = it },
        onModeChange = {
            modeName = it.name
            preferences.edit().putString("mode", it.name).apply()
        },
        onOpenSettings = { showSettings = true },
        onVoiceInput = beginVoiceInput,
        onSendOrStop = {
            if (routing) {
                Unit
            } else if (runState.running) {
                AgentService.stopTask(context)
            } else {
                submitMessage(taskDraft)
            }
        },
    )
}

@Composable
private fun VoiceCommandDialog(
    state: VoiceCommandState,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val title =
        when (state) {
            is VoiceCommandState.Preparing -> "准备离线语音输入"
            is VoiceCommandState.Listening -> "正在聆听"
            is VoiceCommandState.Error -> "语音输入失败"
            else -> "语音命令"
        }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    is VoiceCommandState.Preparing -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            buildString {
                                append(state.message)
                                state.progress?.let { append(" $it%") }
                            },
                        )
                    }
                    is VoiceCommandState.Listening -> {
                        Text(
                            state.text.ifBlank { "请说出聊天内容或手机任务…" },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "识别完全在手机本地进行；说完后点击“完成并发送”。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is VoiceCommandState.Error -> Text(state.message)
                    is VoiceCommandState.Result -> Text(state.text)
                    VoiceCommandState.Idle -> Text("正在启动麦克风…")
                }
            }
        },
        confirmButton = {
            when (state) {
                is VoiceCommandState.Listening ->
                    TextButton(onClick = onFinish) { Text("完成并发送") }
                is VoiceCommandState.Error ->
                    TextButton(onClick = onRetry) { Text("重试") }
                else -> Unit
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(
    taskDraft: String,
    mode: TargetMode,
    runState: AgentRunState,
    messages: List<ConversationMessage>,
    routing: Boolean,
    onTaskDraftChange: (String) -> Unit,
    onModeChange: (TargetMode) -> Unit,
    onOpenSettings: () -> Unit,
    onVoiceInput: () -> Unit,
    onSendOrStop: () -> Unit,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, routing, runState.running) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(
                messages.lastIndex + if (routing || runState.running) 1 else 0,
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "主页面",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text =
                                if (runState.running) {
                                    "Umbra phone-agent · 任务执行中"
                                } else {
                                    "Umbra phone-agent · ${runState.status.ifBlank { "准备接收任务" }}"
                                },
                            style = MaterialTheme.typography.labelSmall,
                            color =
                                if (runState.awaitingTakeover) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        bottomBar = {
            TaskComposer(
                value = taskDraft,
                mode = mode,
                running = runState.running,
                routing = routing,
                onValueChange = onTaskDraftChange,
                onVoiceInput = onVoiceInput,
                onSendOrStop = onSendOrStop,
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            ModeSelector(
                mode = mode,
                enabled = !runState.running,
                onModeChange = onModeChange,
            )
            if (runState.awaitingTakeover) {
                TakeoverAlertDialog(
                    status = runState.status,
                    onApprove = { AgentService.approveTakeover(context = context) },
                    onCancel = { AgentService.stopTask(context = context) },
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(messages) { _, message ->
                    ChatBubble(message)
                }
                if (routing || runState.running) {
                    item {
                        ChatBubble(
                            ConversationMessage(
                                role = ConversationRole.ASSISTANT,
                                text =
                                    if (routing) {
                                        "正在理解你的消息…"
                                    } else {
                                        "任务正在后台执行，完成后我会回复结果。"
                                    },
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TakeoverAlertDialog(
    status: String,
    onApprove: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("任务等待人工接管") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(status)
                Text(
                    "点击“接管到主屏”后，当前虚拟屏页面才会迁移到主屏；在此之前 Agent 已停止继续操作虚拟屏。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onApprove) {
                Text("接管到主屏")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun ModeSelector(
    mode: TargetMode,
    enabled: Boolean,
    onModeChange: (TargetMode) -> Unit,
) {
    Surface(tonalElevation = 1.dp) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "执行屏幕",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TargetMode.entries.forEach { item ->
                FilterChip(
                    selected = mode == item,
                    enabled = enabled,
                    onClick = { onModeChange(item) },
                    label = { Text(item.label) },
                )
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ConversationMessage) {
    val fromUser = message.role == ConversationRole.USER
    val isWarning = message.kind == ConversationKind.ERROR
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement =
            if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!fromUser) {
            Box(
                modifier =
                    Modifier
                        .padding(top = 2.dp, end = 8.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "U",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
            shape =
                if (fromUser) {
                    RoundedCornerShape(20.dp, 4.dp, 20.dp, 20.dp)
                } else {
                    RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp)
                },
            color =
                when {
                    fromUser -> MaterialTheme.colorScheme.primary
                    isWarning -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                color =
                    when {
                        fromUser -> MaterialTheme.colorScheme.onPrimary
                        isWarning -> MaterialTheme.colorScheme.onErrorContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun TaskComposer(
    value: String,
    mode: TargetMode,
    running: Boolean,
    routing: Boolean,
    onValueChange: (String) -> Unit,
    onVoiceInput: () -> Unit,
    onSendOrStop: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                enabled = !running && !routing,
                placeholder = { Text("给 Umbra 一个任务…") },
                supportingText = {
                    Text(
                        when {
                            running -> "任务执行中，点击右侧停止"
                            routing -> "正在判断是聊天还是手机任务"
                            else -> "目标：${mode.label}"
                        },
                    )
                },
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
            )
            IconButton(
                onClick = onVoiceInput,
                modifier = Modifier.padding(bottom = 20.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "离线语音输入",
                )
            }
            FilledIconButton(
                onClick = onSendOrStop,
                enabled = running || (!routing && value.isNotBlank()),
                modifier = Modifier.padding(bottom = 20.dp),
            ) {
                Icon(
                    imageVector =
                        if (running) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (running) "停止任务" else "发送任务",
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    apiKey: String,
    baseUrl: String,
    model: String,
    maxSteps: String,
    mode: TargetMode,
    preview: android.graphics.Bitmap?,
    onBack: () -> Unit,
    onApiKeyChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onMaxStepsChange: (String) -> Unit,
    onModeChange: (TargetMode) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onClearConversation: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (mode == TargetMode.VIRTUAL_DISPLAY && preview != null) {
                item { SettingsSectionTitle("虚拟屏预览") }
                item {
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = "虚拟屏预览",
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.Black),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            item { SettingsSectionTitle("运行环境") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TargetMode.entries.forEach { item ->
                        FilterChip(
                            selected = mode == item,
                            onClick = { onModeChange(item) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
            item {
                OutlinedButton(onClick = onClearConversation) {
                    Text("清空对话记录")
                }
            }
            item {
                Text(
                    text =
                        if (mode == TargetMode.VIRTUAL_DISPLAY) {
                            "任务在隔离虚拟屏中执行，不占用主屏。"
                        } else {
                            "任务直接操作当前主屏。"
                        },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }
            item { SettingsSectionTitle("通用多模态模型") }
            item {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("VLM API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
            item {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = onBaseUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("OpenAI-compatible Base URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
            item {
                OutlinedTextField(
                    value = model,
                    onValueChange = onModelChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("模型") },
                    singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    value = maxSteps,
                    onValueChange = onMaxStepsChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("最大动作步数") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }
            item { SettingsSectionTitle("权限与连接") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenAccessibilitySettings) {
                        Text("无障碍服务")
                    }
                    OutlinedButton(onClick = onRequestShizukuPermission) {
                        Text("Shizuku 权限")
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(20.dp)) }
            item {
                Text(
                    text = "版本号：${BuildConfig.VERSION_NAME}",
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}
