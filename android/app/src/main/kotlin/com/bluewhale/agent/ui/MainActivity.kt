package com.bluewhale.agent.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bluewhale.agent.model.AutoGlmConfig
import com.bluewhale.agent.model.TargetMode
import com.bluewhale.agent.service.AgentService
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BluewhaleApp(
                        context = this,
                        onOpenAccessibilitySettings = ::openAccessibilitySettings,
                        onRequestShizukuPermission = ::requestShizukuPermission,
                    )
                }
            }
        }
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
}

@Composable
private fun BluewhaleApp(
    context: Context,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
) {
    val preferences = context.getSharedPreferences("bluewhale_prefs", Context.MODE_PRIVATE)
    var apiKey by rememberSaveable { mutableStateOf(preferences.getString("api_key", "").orEmpty()) }
    var baseUrl by rememberSaveable {
        mutableStateOf(
            preferences
                .getString("base_url", "https://open.bigmodel.cn/api/paas/v4")
                .orEmpty(),
        )
    }
    var model by rememberSaveable {
        mutableStateOf(preferences.getString("model", "autoglm-phone").orEmpty())
    }
    var maxSteps by rememberSaveable {
        mutableStateOf(preferences.getInt("max_steps", 40).toString())
    }
    var task by rememberSaveable { mutableStateOf("") }
    var modeName by rememberSaveable {
        mutableStateOf(preferences.getString("mode", TargetMode.MAIN_SCREEN.name).orEmpty())
    }

    val runState by AgentService.state.collectAsStateWithLifecycle()
    val preview by AgentService.preview.collectAsStateWithLifecycle()
    val mode =
        runCatching { TargetMode.valueOf(modeName) }.getOrDefault(TargetMode.MAIN_SCREEN)

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Bluewhale Agent", style = MaterialTheme.typography.headlineMedium)
        Text(
            "选择主屏执行，或选择虚拟屏让任务在后台运行。",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TargetMode.entries.forEach { item ->
                FilterChip(
                    selected = mode == item,
                    onClick = {
                        modeName = item.name
                        preferences.edit().putString("mode", item.name).apply()
                    },
                    label = { Text(item.label) },
                )
            }
        }

        OutlinedTextField(
            value = task,
            onValueChange = { task = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("任务") },
            placeholder = { Text("例如：打开微信，搜索天气") },
            minLines = 2,
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("AutoGLM API Key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Base URL") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )

        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("模型") },
            singleLine = true,
        )

        OutlinedTextField(
            value = maxSteps,
            onValueChange = { value ->
                maxSteps = value.filter { it.isDigit() }
                preferences.edit().putInt("max_steps", value.filter { it.isDigit() }.toIntOrNull() ?: 40).apply()
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("最大步数") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !runState.running && task.isNotBlank() && apiKey.isNotBlank(),
                onClick = {
                    preferences
                        .edit()
                        .putString("api_key", apiKey)
                        .putString("base_url", baseUrl)
                        .putString("model", model)
                        .putString("mode", mode.name)
                        .apply()
                    AgentService.startTask(
                        context,
                        task.trim(),
                        mode,
                        AutoGlmConfig(
                            apiKey = apiKey.trim(),
                            baseUrl = baseUrl.trim(),
                            model = model.trim(),
                            maxSteps = maxSteps.toIntOrNull() ?: 40,
                        ),
                    )
                },
            ) {
                Text(if (runState.running) "运行中" else "开始任务")
            }
            OutlinedButton(
                enabled = runState.running,
                onClick = { AgentService.stopTask(context) },
            ) {
                Text("停止")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenAccessibilitySettings) {
                Text("无障碍设置")
            }
            OutlinedButton(onClick = onRequestShizukuPermission) {
                Text("Shizuku 权限")
            }
        }

        if (mode == TargetMode.VIRTUAL_DISPLAY && preview != null) {
            Text("虚拟屏预览", style = MaterialTheme.typography.titleMedium)
            Image(
                bitmap = preview!!.asImageBitmap(),
                contentDescription = "虚拟屏预览",
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .padding(vertical = 4.dp),
            )
        }

        Text("状态", style = MaterialTheme.typography.titleMedium)
        Text(
            runState.status.ifBlank { "未运行" },
            style = MaterialTheme.typography.bodyLarge,
        )

        Spacer(modifier = Modifier.height(4.dp))
        Text("日志", style = MaterialTheme.typography.titleMedium)
        if (runState.logs.isEmpty()) {
            Text("暂无日志", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(modifier = Modifier.height(220.dp)) {
                items(runState.logs.reversed()) { log ->
                    Text(
                        log,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}