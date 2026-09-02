package com.bluewhale.agent.platform

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Build
import android.util.Log
import android.os.Bundle
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.bluewhale.agent.BuildConfig
import com.bluewhale.agent.model.AccessibilitySnapshot
import com.bluewhale.agent.model.ActionResult
import com.bluewhale.agent.model.DeviceAction
import com.bluewhale.agent.model.ScreenCapture
import com.bluewhale.agent.model.TargetMode
import com.bluewhale.agent.perception.AccessibilityTreeExtractor
import com.bluewhale.agent.virtualdisplay.ShizukuVirtualDisplayClient
import com.bluewhale.agent.virtualdisplay.VirtualDisplayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class VirtualDisplayAgentPlatform(
    private val service: AccessibilityService,
) : AgentPlatform {
    override val mode = TargetMode.VIRTUAL_DISPLAY

    companion object {
        private const val TAG = "BluewhaleVirtualDisplay"

        // public | own content only | can show with insecure keyguard | destroy content on removal | system decorations | trusted
        private const val DISPLAY_FLAGS = 0x1 or 0x8 or 0x40 or 0x200 or 0x400 or 0x800
        private const val SURFACE_READY_DELAY_MS = 350L
        private const val IMAGE_READER_MAX_IMAGES = 4
        private const val FIRST_FRAME_TIMEOUT_MS = 2_000L
        private const val RECOVERY_FRAME_TIMEOUT_MS = 3_000L
        private const val FRAME_POLL_MS = 100L
    }

    private val client = ShizukuVirtualDisplayClient()
    private var config = VirtualDisplayConfig.fromPhysicalDisplay(service)
    private val lifecycleMutex = Mutex()
    private val captureMutex = Mutex()

    private val _preview = MutableStateFlow<Bitmap?>(null)
    override val preview: StateFlow<Bitmap?> = _preview

    @Volatile
    private var displayId: Int = Display.INVALID_DISPLAY

    @Volatile
    private var displayRotation: Int = Surface.ROTATION_0

    @Volatile
    private var capturePaused: Boolean = false

    @Volatile
    private var imageReader: ImageReader? = null

    @Volatile
    private var lastSuccessfulFrame: Bitmap? = null

    @Volatile
    private var lastSuccessfulFrameId: Long = 0L

    @Volatile
    private var lastSuccessfulFrameAtMs: Long = 0L

    @Volatile
    private var frameSequence: Long = 0L

    @Volatile
    private var lastLaunchedPackage: String? = null

    @Volatile
    private var lastTapPoint: Pair<Int, Int>? = null

    override fun isAvailable(): Boolean = client.isAvailable() && client.hasPermission()

    override suspend fun start() {
        lifecycleMutex.withLock {
            if (displayId != Display.INVALID_DISPLAY) return
            client.bypassHiddenApis()

            val reader =
                ImageReader.newInstance(
                    config.width,
                    config.height,
                    PixelFormat.RGBA_8888,
                    IMAGE_READER_MAX_IMAGES,
                )
            val createdId =
                try {
                    client.createVirtualDisplay(
                        name = "bluewhale_agent_display",
                        width = config.width,
                        height = config.height,
                        densityDpi = config.densityDpi,
                        surface = reader.surface,
                        flags = DISPLAY_FLAGS,
                    )
                } catch (error: Exception) {
                    reader.close()
                    throw error
                }

            if (createdId < 0) {
                reader.close()
                error("无法创建虚拟屏，请确认 Shizuku 已运行并已授权")
            }

            displayId = createdId
            imageReader = reader
            capturePaused = false
            displayRotation = currentDisplay()?.rotation ?: Surface.ROTATION_0
        }
        delay(SURFACE_READY_DELAY_MS)
    }

    override suspend fun stop() {
        captureMutex.withLock {
            lifecycleMutex.withLock {
                val oldId = displayId
                displayId = Display.INVALID_DISPLAY
                val reader = imageReader
                imageReader = null

                if (oldId != Display.INVALID_DISPLAY) {
                    val removed = client.removeRootTasksOnDisplay(oldId)
                    if (removed < 0) {
                        stopLaunchedAppOnVirtualDisplay()
                    }
                    lastLaunchedPackage = null
                    delay(200)
                    client.releaseVirtualDisplay(oldId)
                }
                lastTapPoint = null
                _preview.value = null
                reader?.close()
                lastSuccessfulFrame?.recycle()
                lastSuccessfulFrame = null
                lastSuccessfulFrameId = 0L
                lastSuccessfulFrameAtMs = 0L
                capturePaused = false
                displayRotation = Surface.ROTATION_0
                client.clear()
            }
        }
    }

    private fun currentDisplay(): Display? {
        val currentDisplay = displayId
        if (currentDisplay == Display.INVALID_DISPLAY) return null
        return runCatching {
            service.getSystemService(DisplayManager::class.java).getDisplay(currentDisplay)
        }.getOrNull()
    }

    private fun displayMetrics(display: Display): DisplayMetrics =
        DisplayMetrics().also { display.getRealMetrics(it) }

    private suspend fun syncDisplayRotation() =
        lifecycleMutex.withLock {
            val currentDisplay = displayId
            if (currentDisplay == Display.INVALID_DISPLAY) return@withLock
            val display = currentDisplay() ?: return@withLock
            val rotation = display.rotation
            val metrics = displayMetrics(display)
            if (
                rotation == displayRotation &&
                metrics.widthPixels == config.width &&
                metrics.heightPixels == config.height
            ) {
                return@withLock
            }

            val replacement =
                ImageReader.newInstance(
                    metrics.widthPixels,
                    metrics.heightPixels,
                    PixelFormat.RGBA_8888,
                    IMAGE_READER_MAX_IMAGES,
                )
            if (!client.setVirtualDisplaySurface(currentDisplay, replacement.surface)) {
                replacement.close()
                return@withLock
            }

            val previous = imageReader
            imageReader = replacement
            previous?.close()
            config =
                VirtualDisplayConfig(
                    width = metrics.widthPixels,
                    height = metrics.heightPixels,
                    densityDpi = metrics.densityDpi,
                    density = metrics.density,
                )
            displayRotation = rotation
            Log.i(
                TAG,
                "Virtual display rotation synced rotation=$rotation size=${metrics.widthPixels}x${metrics.heightPixels}",
            )
            delay(SURFACE_READY_DELAY_MS)
        }

    override suspend fun captureScreen(): ScreenCapture = captureMutex.withLock {
        if (capturePaused) {
            error("已请求人工接管，停止虚拟屏感知")
        }
        syncDisplayRotation()
        var reader = imageReader ?: error("虚拟屏未启动")
        var image = awaitImage(reader, FIRST_FRAME_TIMEOUT_MS)
        if (image == null) {
            val recovered = recoverImageReader()
            if (recovered != null) {
                reader = recovered
                image = awaitImage(reader, RECOVERY_FRAME_TIMEOUT_MS)
            }
        }
        val acquired = image
        if (acquired == null) {
            val cached = lastSuccessfulFrame
                ?: error("无法获取虚拟屏图像，且没有可用的历史帧")
            Log.w(TAG, "No fresh virtual-display frame; using last successful frame")
            val bitmap = cached.copy(cached.config ?: Bitmap.Config.ARGB_8888, false)
            return@withLock ScreenCapture(
                width = bitmap.width,
                height = bitmap.height,
                bitmap = bitmap,
                frameId = lastSuccessfulFrameId,
                capturedAtElapsedRealtimeMs = lastSuccessfulFrameAtMs,
                isFresh = false,
                source = "virtual_display_cache",
            )
        }

        withContext(Dispatchers.Default) {
            try {
                val plane = acquired.planes[0]
                val pixelStride = plane.pixelStride
                if (pixelStride == 0) error("虚拟屏图像 pixelStride 为 0")
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * acquired.width

                val raw =
                    Bitmap.createBitmap(
                        acquired.width + rowPadding / pixelStride,
                        acquired.height,
                        Bitmap.Config.ARGB_8888,
                    )
                raw.copyPixelsFromBuffer(plane.buffer)

                val bitmap =
                    if (raw.width > config.width || raw.height > config.height) {
                        Bitmap.createBitmap(raw, 0, 0, config.width, config.height).also {
                            if (it !== raw) raw.recycle()
                        }
                    } else {
                        raw
                    }

                lastSuccessfulFrame?.recycle()
                lastSuccessfulFrame =
                    bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
                frameSequence += 1
                lastSuccessfulFrameId = frameSequence
                lastSuccessfulFrameAtMs = SystemClock.elapsedRealtime()
                _preview.value = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)

                ScreenCapture(
                    width = bitmap.width,
                    height = bitmap.height,
                    bitmap = bitmap,
                    frameId = lastSuccessfulFrameId,
                    capturedAtElapsedRealtimeMs = lastSuccessfulFrameAtMs,
                    isFresh = true,
                    source = "virtual_display_image_reader",
                )
            } finally {
                acquired.close()
            }
        }
    }

    private suspend fun awaitImage(
        reader: ImageReader,
        timeoutMs: Long,
    ): android.media.Image? {
        val startedAt = System.nanoTime()
        while ((System.nanoTime() - startedAt) / 1_000_000L < timeoutMs) {
            val image = runCatching { reader.acquireLatestImage() }.getOrNull()
            if (image != null) return image
            delay(FRAME_POLL_MS)
        }
        return null
    }

    private suspend fun recoverImageReader(): ImageReader? =
        lifecycleMutex.withLock {
            val currentDisplay = displayId
            if (currentDisplay == Display.INVALID_DISPLAY) return@withLock null
            val replacement =
                ImageReader.newInstance(
                    config.width,
                    config.height,
                    PixelFormat.RGBA_8888,
                    IMAGE_READER_MAX_IMAGES,
                )
            if (!client.setVirtualDisplaySurface(currentDisplay, replacement.surface)) {
                replacement.close()
                return@withLock null
            }
            val previous = imageReader
            imageReader = replacement
            previous?.close()
            delay(SURFACE_READY_DELAY_MS)
            replacement
        }
    override suspend fun captureAccessibility(): AccessibilitySnapshot {
        if (capturePaused) return AccessibilitySnapshot()
        syncDisplayRotation()
        val root = rootOnDisplay()
        return try {
            AccessibilityTreeExtractor.extract(root)
        } finally {
            root?.recycle()
        }
    }

    override suspend fun performAction(action: DeviceAction): ActionResult {
        if (capturePaused && action !is DeviceAction.TakeOver) {
            return ActionResult.Failure("已请求人工接管，虚拟屏不再执行自动动作")
        }
        syncDisplayRotation()
        val currentDisplay = displayId
        if (currentDisplay == Display.INVALID_DISPLAY) {
            return ActionResult.Failure("虚拟屏未启动")
        }

        val result =
            try {
                dispatchAction(action, currentDisplay)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ActionResult.Failure(error.message ?: "虚拟屏动作失败", error)
            }

        val settleDelayMs =
            when (action) {
                is DeviceAction.Launch -> 700L
                is DeviceAction.Tap -> 550L
                is DeviceAction.Swipe -> 450L
                is DeviceAction.Type -> 300L
                DeviceAction.Back -> 450L
                else -> 0L
            }
        if (settleDelayMs > 0L) {
            delay(settleDelayMs)
        }
        return result
    }

    private suspend fun dispatchAction(
        action: DeviceAction,
        currentDisplay: Int,
    ): ActionResult =
        when (action) {
            is DeviceAction.Launch -> launchApp(action.app)
            is DeviceAction.Tap -> tapOrFocus(action)
            is DeviceAction.Swipe ->
                shellInput(
                    listOf(
                        "input", "-d", "$currentDisplay", "swipe",
                        "${action.startX}", "${action.startY}",
                        "${action.endX}", "${action.endY}",
                        "${action.durationMs}",
                    ),
                )
            is DeviceAction.Type -> typeText(action, currentDisplay)
            DeviceAction.Back ->
                shellInput(listOf("input", "-d", "$currentDisplay", "keyevent", "4"))
            is DeviceAction.Wait -> {
                delay(action.durationMs.toLong())
                ActionResult.Success("等待完成")
            }
            is DeviceAction.TakeOver ->
                ActionResult.Success("请求人工接管：${action.message}")
            is DeviceAction.SystemTool ->
                SystemCapabilityExecutor.executeOnVirtualDisplay(
                    context = service,
                    client = client,
                    displayId = currentDisplay,
                    capability = action.capability,
                )
        }

    override suspend fun isTextInputFocused(): Boolean {
        val root = rootOnDisplay()
        val node = TextNodeSupport.findFocusedTextNode(root) ?: TextNodeSupport.findFirstTextNode(root)
        if (node !== root) node?.recycle()
        root?.recycle()
        return node != null
    }

    private suspend fun launchApp(appName: String): ActionResult {
        val packageName = AppPackages.resolve(service.packageManager, appName)
            ?: return ActionResult.Failure("未找到应用：$appName")
        val launchIntent =
            service.packageManager.getLaunchIntentForPackage(packageName)
                ?: return ActionResult.Failure("应用不可启动：$packageName")
        val component =
            launchIntent.component?.flattenToShortString()
                ?: return ActionResult.Failure("无法解析应用入口：$packageName")
        val code =
            client.executeShellCommand(
                arrayOf("am", "start", "-n", component, "--display", "$displayId"),
            )
        return if (code == 0) {
            lastLaunchedPackage = packageName
            ActionResult.Success(
                "已在虚拟屏启动 $appName",
                metadata = mapOf("packageName" to packageName),
            )
        } else {
            ActionResult.Failure("在虚拟屏启动 $appName 失败，退出码 $code")
        }
    }

    private suspend fun typeText(
        action: DeviceAction.Type,
        currentDisplay: Int,
    ): ActionResult {
        val text = action.text
        if (text.isEmpty()) return ActionResult.Failure("文本为空")

        val point =
            if (action.targetX != null && action.targetY != null) {
                action.targetX to action.targetY
            } else {
                lastTapPoint
            }
        lastTapPoint = null
        Log.i(
            TAG,
            "typeText display=$currentDisplay " +
                if (BuildConfig.DEBUG) {
                    "text=$text target=$point strategy=${action.strategy}"
                } else {
                    "text=<redacted:${text.length}> target=$point strategy=${action.strategy}"
                },
        )

        tryDirectNodeWrite(text, point, action.strategy)?.let { return it }

        if (mainScreenImeVisible()) {
            return ActionResult.Failure(
                "主屏输入法正在使用中，已拒绝虚拟屏剪贴板/焦点回退；请 Wait 后重试或 Take_over",
            )
        }

        if (point != null) {
            val focused =
                shellInput(
                    listOf(
                        "input",
                        "-d",
                        "$currentDisplay",
                        "tap",
                        "${point.first}",
                        "${point.second}",
                    ),
                )
            if (focused is ActionResult.Failure) return focused
            delay(180)
            tryDirectNodeWrite(text, point, "targeted_tap_then_set_text")?.let { return it }
        }

        val pastedOnNode =
            withTemporaryClipboard(text) {
                val pasteRoot = rootOnDisplay()
                val pasteNode =
                    if (point != null) {
                        TextNodeSupport.findTextNodeAt(pasteRoot, point.first, point.second)
                    } else {
                        null
                    } ?: TextNodeSupport.findFocusedTextNode(pasteRoot)
                        ?: TextNodeSupport.findFirstTextNode(pasteRoot)
                try {
                    if (pasteNode != null) {
                        TextNodeSupport.pasteText(pasteNode).also {
                            if (it) TextNodeSupport.clearInputFocus(pasteNode)
                        }
                    } else {
                        false
                    }
                } finally {
                    if (pasteNode !== pasteRoot) pasteNode?.recycle()
                    pasteRoot?.recycle()
                }
            }
        if (pastedOnNode) {
            return ActionResult.Success(
                "已通过无障碍节点粘贴文本",
                metadata =
                    mapOf(
                        "input_method" to "ACTION_PASTE",
                        "input_strategy" to action.strategy,
                        "input_dispatched" to "true",
                    ),
            )
        }

        val pastedByKey = withTemporaryClipboard(text) {
            client.executeShellCommand(
                arrayOf("input", "-d", "$currentDisplay", "keyevent", "279"),
            ) == 0
        }
        if (pastedByKey) clearFocusedVirtualInput()
        return if (pastedByKey) {
            ActionResult.Success(
                "已通过虚拟屏粘贴键输入文本",
                metadata =
                    mapOf(
                        "input_method" to "KEYCODE_PASTE",
                        "input_strategy" to action.strategy,
                        "input_dispatched" to "true",
                    ),
            )
        } else {
            ActionResult.Failure("虚拟屏未找到可输入控件，且非 IME 输入失败")
        }
    }

    override suspend fun pauseForTakeover() {
        capturePaused = true
    }

    override suspend fun handoffToMainScreen(): ActionResult =
        lifecycleMutex.withLock {
            val currentDisplay = displayId
            if (currentDisplay == Display.INVALID_DISPLAY) {
                return@withLock ActionResult.Failure("虚拟屏已经释放，无法接管")
            }
            val moved =
                withContext(Dispatchers.IO) {
                    client.moveVisibleRootTaskToDisplay(
                        fromDisplayId = currentDisplay,
                        toDisplayId = Display.DEFAULT_DISPLAY,
                    )
                }
            when {
                moved > 0 -> ActionResult.Success("虚拟屏当前页面已迁移到主屏")
                moved == 0 -> ActionResult.Failure("虚拟屏没有可迁移的应用任务")
                else -> ActionResult.Failure("系统拒绝迁移任务，请在虚拟屏完成操作或重试")
            }
        }

    private fun tryDirectNodeWrite(
        text: String,
        point: Pair<Int, Int>?,
        strategy: String,
    ): ActionResult.Success? {
        val root = rootOnDisplay()
        val node =
            if (point != null) {
                TextNodeSupport.findTextNodeAt(root, point.first, point.second)
            } else {
                null
            } ?: TextNodeSupport.findFocusedTextNode(root)
                ?: TextNodeSupport.findFirstTextNode(root)
        if (node == null) {
            root?.recycle()
            return null
        }
        return try {
            Log.i(
                TAG,
                "typeText nodeClass=${node.className} editable=${node.isEditable} " +
                    if (BuildConfig.DEBUG) {
                        "text=${node.text}"
                    } else {
                        "text=<redacted:${node.text?.length ?: 0}>"
                    },
            )
            val written = TextNodeSupport.writeText(node, text)
            val readBack = written && TextNodeSupport.containsText(node, text)
            if (!written) {
                null
            } else {
                TextNodeSupport.clearInputFocus(node)
                ActionResult.Success(
                    if (readBack) {
                        "已直接向虚拟屏目标节点写入文本并已验证"
                    } else {
                        "已直接向虚拟屏目标节点写入文本，回读暂未确认"
                    },
                    metadata =
                        mapOf(
                            "input_method" to "ACTION_SET_TEXT",
                            "input_strategy" to strategy,
                            "input_dispatched" to "true",
                            "verified" to readBack.toString(),
                        ),
                )
            }
        } finally {
            if (node !== root) node.recycle()
            root?.recycle()
        }
    }

    private fun mainScreenImeVisible(): Boolean {
        val windows = windowsOnDisplay(Display.DEFAULT_DISPLAY)
        return try {
            windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } finally {
            windows.forEach { window -> runCatching { window.recycle() } }
        }
    }

    private fun clearFocusedVirtualInput() {
        val root = rootOnDisplay()
        val node = TextNodeSupport.findFocusedTextNode(root)
        try {
            if (node != null) TextNodeSupport.clearInputFocus(node)
        } finally {
            if (node !== root) node?.recycle()
            root?.recycle()
        }
    }

    private fun tapOrFocus(action: DeviceAction.Tap): ActionResult {
        lastTapPoint = action.x to action.y
        return shellInput(
            listOf("input", "-d", "$displayId", "tap", "${action.x}", "${action.y}"),
        )
    }
    private suspend fun <T> withTemporaryClipboard(
        text: String,
        block: suspend () -> T,
    ): T {
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val previous = clipboard.primaryClip
        clipboard.setPrimaryClip(ClipData.newPlainText("bluewhale", text))
        return try {
            block()
        } finally {
            delay(160)
            restoreClipboard(clipboard, previous)
        }
    }

    private fun restoreClipboard(clipboard: ClipboardManager, previous: ClipData?) {
        runCatching {
            if (previous != null) {
                clipboard.setPrimaryClip(previous)
            } else {
                clipboard.clearPrimaryClip()
            }
        }
    }

    private fun rootOnDisplay(): AccessibilityNodeInfo? {
        val currentDisplay = displayId
        if (currentDisplay == Display.INVALID_DISPLAY) return null

        val windows = windowsOnDisplay(currentDisplay)
        return try {
            val eligible =
                windows
                    .filter {
                        it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                            it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD
                    }
                    .sortedByDescending { it.layer }
            val top =
                eligible.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                    ?: eligible.firstOrNull()
            top?.root
        } finally {
            windows.forEach { window ->
                runCatching { window.recycle() }
            }
        }
    }

    private fun windowsOnDisplay(displayId: Int): List<AccessibilityWindowInfo> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                service.getWindowsOnAllDisplays().get(displayId) ?: emptyList()
            } else {
                service.windows?.filter { it.displayId == displayId } ?: emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isFocused) return node
        for (index in 0 until node.childCount) {
            val found = findFocusedEditable(node.getChild(index))
            if (found != null) return found
        }
        return null
    }

    private fun shellInput(arguments: List<String>): ActionResult {
        val code = client.executeShellCommand(arguments.toTypedArray())
        return if (code == 0) {
            ActionResult.Success("命令执行成功")
        } else {
            ActionResult.Failure("命令执行失败，退出码 $code")
        }
    }



    private fun stopLaunchedAppOnVirtualDisplay() {
        val packageName = lastLaunchedPackage ?: return
        lastLaunchedPackage = null
        runCatching {
            client.executeShellCommand(arrayOf("am", "force-stop", packageName))
        }
    }

}
