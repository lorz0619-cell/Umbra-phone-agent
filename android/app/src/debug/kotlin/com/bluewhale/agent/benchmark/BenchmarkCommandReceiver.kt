package com.bluewhale.agent.benchmark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import com.bluewhale.agent.debug.AgentLogcat
import com.bluewhale.agent.model.AgentPhase
import com.bluewhale.agent.model.AgentTraceEvent
import com.bluewhale.agent.model.AgentTraceKind
import com.bluewhale.agent.model.AgentTraceLevel
import com.bluewhale.agent.model.DEFAULT_VLM_BASE_URL
import com.bluewhale.agent.model.DEFAULT_VLM_MODEL
import com.bluewhale.agent.model.TargetMode
import com.bluewhale.agent.model.VlmConfig
import com.bluewhale.agent.service.AgentService

/**
 * Shell-only bridge used by tools/benchmark/run-benchmark.ps1.
 *
 * The release manifest never contains this receiver. The debug manifest additionally protects it
 * with android.permission.DUMP, which ADB shell has but regular third-party applications do not.
 * The API key is read from app-private preferences and is never sent over ADB.
 */
class BenchmarkCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP -> AgentService.stopTask(context)
            ACTION_RUN -> startBenchmark(context, intent)
        }
    }

    private fun startBenchmark(context: Context, intent: Intent) {
        val runId =
            intent.getStringExtra(EXTRA_RUN_ID)
                .orEmpty()
                .takeIf { it.matches(RUN_ID_PATTERN) }
                ?: return reject("invalid-run-id", "Benchmark run id is missing or invalid")
        val task =
            runCatching {
                val encoded = intent.getStringExtra(EXTRA_TASK_BASE64).orEmpty()
                String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8).trim()
            }.getOrDefault("")
        if (task.isBlank() || task.length > MAX_TASK_LENGTH) {
            reject(runId, "Benchmark task must contain 1..$MAX_TASK_LENGTH characters")
            return
        }

        val mode =
            runCatching {
                TargetMode.valueOf(intent.getStringExtra(EXTRA_MODE).orEmpty())
            }.getOrDefault(TargetMode.MAIN_SCREEN)
        val maxSteps = intent.getIntExtra(EXTRA_MAX_STEPS, 40).coerceIn(1, 200)
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = preferences.getString(KEY_API_KEY, "").orEmpty().trim()
        if (apiKey.isBlank()) {
            reject(runId, "Configure the model API key in Umbra settings before benchmarking")
            return
        }
        val config =
            VlmConfig(
                apiKey = apiKey,
                baseUrl =
                    preferences.getString(KEY_BASE_URL, DEFAULT_VLM_BASE_URL)
                        .orEmpty()
                        .ifBlank { DEFAULT_VLM_BASE_URL },
                model =
                    preferences.getString(KEY_MODEL, DEFAULT_VLM_MODEL)
                        .orEmpty()
                        .ifBlank { DEFAULT_VLM_MODEL },
                maxSteps = maxSteps,
            )

        AgentLogcat.write(
            AgentTraceEvent(
                kind = AgentTraceKind.PHASE,
                step = 0,
                phase = AgentPhase.IDLE,
                title = "Benchmark command accepted",
                fields = mapOf("benchmark_run_id" to runId),
            ),
        )
        AgentService.startTask(
            context = context,
            task = task,
            mode = mode,
            config = config,
            maxSteps = maxSteps,
            benchmarkRunId = runId,
        )
    }

    private fun reject(runId: String, reason: String) {
        AgentLogcat.write(
            AgentTraceEvent(
                kind = AgentTraceKind.ERROR,
                step = 0,
                phase = AgentPhase.FAILED,
                title = "Benchmark command rejected",
                message = reason,
                fields = mapOf("benchmark_run_id" to runId),
                level = AgentTraceLevel.ERROR,
            ),
        )
    }

    private companion object {
        const val ACTION_RUN = "com.bluewhale.agent.debug.RUN_BENCHMARK"
        const val ACTION_STOP = "com.bluewhale.agent.debug.STOP_BENCHMARK"
        const val EXTRA_TASK_BASE64 = "task_base64"
        const val EXTRA_RUN_ID = "run_id"
        const val EXTRA_MODE = "mode"
        const val EXTRA_MAX_STEPS = "max_steps"
        const val PREFS_NAME = "bluewhale_prefs"
        const val KEY_API_KEY = "api_key"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val MAX_TASK_LENGTH = 4_000
        val RUN_ID_PATTERN = Regex("[A-Za-z0-9._-]{1,80}")
    }
}
