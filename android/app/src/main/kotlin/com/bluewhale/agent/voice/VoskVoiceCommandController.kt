package com.bluewhale.agent.voice

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

sealed interface VoiceCommandState {
    data object Idle : VoiceCommandState
    data class Preparing(val progress: Int?, val message: String) : VoiceCommandState
    data class Listening(val text: String) : VoiceCommandState
    data class Result(val text: String) : VoiceCommandState
    data class Error(val message: String) : VoiceCommandState
}

/**
 * On-device Chinese command recognition backed by the Apache-2.0 Vosk Android library.
 * The 42 MB official small Chinese model is downloaded once into app-private storage.
 */
class VoskVoiceCommandController(context: Context) : RecognitionListener {
    companion object {
        private const val MODEL_NAME = "vosk-model-small-cn-0.22"
        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
        private const val SAMPLE_RATE = 16_000.0f
        private const val LISTEN_TIMEOUT_MS = 15_000
    }

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<VoiceCommandState>(VoiceCommandState.Idle)
    val state: StateFlow<VoiceCommandState> = _state

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private val finalizedSegments = mutableListOf<String>()
    private var partialText: String = ""

    suspend fun prepareAndListen() {
        if (_state.value is VoiceCommandState.Preparing || _state.value is VoiceCommandState.Listening) {
            return
        }
        stopInternal()
        finalizedSegments.clear()
        partialText = ""
        try {
            val readyModel =
                model ?: withContext(Dispatchers.IO) {
                    val directory = ensureModelDirectory()
                    _state.value = VoiceCommandState.Preparing(null, "正在载入中文语音模型…")
                    Model(directory.absolutePath).also { model = it }
                }
            val nextRecognizer = Recognizer(readyModel, SAMPLE_RATE)
            val nextService = SpeechService(nextRecognizer, SAMPLE_RATE)
            recognizer = nextRecognizer
            speechService = nextService
            _state.value = VoiceCommandState.Listening("")
            if (!nextService.startListening(this, LISTEN_TIMEOUT_MS)) {
                error("麦克风正在使用中")
            }
        } catch (error: Exception) {
            stopInternal()
            _state.value = VoiceCommandState.Error(error.message ?: "语音识别启动失败")
        }
    }

    fun finishListening() {
        // SpeechService.stop() posts exactly one onFinalResult after its worker exits.
        // Let that callback publish the command so the UI cannot submit it twice.
        if (speechService?.stop() != true) {
            _state.value = VoiceCommandState.Error("语音识别已经停止，请重试")
        }
    }

    fun cancel() {
        stopInternal()
        _state.value = VoiceCommandState.Idle
    }

    fun close() {
        stopInternal()
        runCatching { model?.close() }
        model = null
        _state.value = VoiceCommandState.Idle
    }

    override fun onPartialResult(hypothesis: String?) {
        partialText = jsonText(hypothesis, "partial")
        _state.value = VoiceCommandState.Listening(combinedText(partialText))
    }

    override fun onResult(hypothesis: String?) {
        val text = jsonText(hypothesis, "text")
        if (text.isNotBlank()) finalizedSegments += text
        partialText = ""
        _state.value = VoiceCommandState.Listening(combinedText(""))
    }

    override fun onFinalResult(hypothesis: String?) {
        val text = jsonText(hypothesis, "text")
        if (text.isNotBlank()) finalizedSegments += text
        val result = combinedText("")
        stopInternal()
        _state.value =
            if (result.isBlank()) VoiceCommandState.Error("没有识别到语音，请重试")
            else VoiceCommandState.Result(result)
    }

    override fun onError(error: Exception?) {
        stopInternal()
        _state.value = VoiceCommandState.Error(error?.message ?: "语音识别失败")
    }

    override fun onTimeout() {
        val current = combinedText(partialText)
        stopInternal()
        _state.value =
            if (current.isBlank()) VoiceCommandState.Error("没有识别到语音，请重试")
            else VoiceCommandState.Result(current)
    }

    private fun combinedText(partial: String): String =
        (finalizedSegments + partial)
            .filter(String::isNotBlank)
            .joinToString("")
            .trim()

    private fun jsonText(json: String?, key: String): String =
        runCatching { JSONObject(json.orEmpty()).optString(key).trim() }.getOrDefault("")

    private fun stopInternal() {
        runCatching { speechService?.stop() }
        runCatching { speechService?.shutdown() }
        speechService = null
        runCatching { recognizer?.close() }
        recognizer = null
    }

    private fun ensureModelDirectory(): File {
        val voiceRoot = File(appContext.filesDir, "voice")
        val modelDirectory = File(voiceRoot, MODEL_NAME)
        if (isValidModel(modelDirectory)) return modelDirectory

        voiceRoot.mkdirs()
        val archive = File(voiceRoot, "$MODEL_NAME.zip.part")
        val extracting = File(voiceRoot, "$MODEL_NAME.extracting")
        extracting.deleteRecursively()
        downloadModel(archive)
        unzipSafely(archive, extracting)
        archive.delete()

        val extractedModel = File(extracting, MODEL_NAME)
        val source = if (isValidModel(extractedModel)) extractedModel else extracting
        if (!isValidModel(source)) error("下载的 Vosk 中文模型不完整")
        modelDirectory.deleteRecursively()
        if (!source.renameTo(modelDirectory)) {
            source.copyRecursively(modelDirectory, overwrite = true)
        }
        extracting.deleteRecursively()
        return modelDirectory
    }

    private fun downloadModel(target: File) {
        _state.value = VoiceCommandState.Preparing(0, "首次使用：下载中文语音模型（约 42 MB）")
        val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            connection.connect()
            if (connection.responseCode !in 200..299) {
                error("语音模型下载失败：HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(target, false).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        val progress =
                            if (total > 0) ((copied * 100L) / total).toInt().coerceIn(0, 100)
                            else null
                        _state.value =
                            VoiceCommandState.Preparing(progress, "正在下载中文语音模型…")
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun unzipSafely(archive: File, destination: File) {
        _state.value = VoiceCommandState.Preparing(null, "正在安装中文语音模型…")
        destination.mkdirs()
        val canonicalRoot = destination.canonicalFile
        ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val output = File(destination, entry.name).canonicalFile
                if (!output.path.startsWith(canonicalRoot.path + File.separator)) {
                    error("语音模型压缩包包含非法路径")
                }
                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    FileOutputStream(output).use { zip.copyTo(it) }
                }
                zip.closeEntry()
            }
        }
    }

    private fun isValidModel(directory: File): Boolean =
        File(directory, "am/final.mdl").isFile &&
            File(directory, "conf/model.conf").isFile &&
            File(directory, "graph/HCLr.fst").isFile
}
