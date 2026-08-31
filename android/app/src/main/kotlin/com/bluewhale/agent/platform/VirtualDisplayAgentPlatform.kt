package com.bluewhale.agent.platform

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.os.Build
import android.util.Log
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import com.bluewhale.agent.input.SilentImeController
import com.bluewhale.agent.model.ActionResult
import com.bluewhale.agent.model.AgentAction
import com.bluewhale.agent.model.ScreenCapture
import com.bluewhale.agent.model.TargetMode
import com.bluewhale.agent.virtualdisplay.ShizukuVirtualDisplayClient
import com.bluewhale.agent.virtualdisplay.VirtualDisplayConfig
import kotlinx.coroutines.Dispatchers
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
        private const val IMAGE_READER_MAX_IMAGES = 2
        private val keyboardTriggerActions =
            setOf("launch", "tap", "swipe", "long press", "double tap", "type")
    }

    private val client = ShizukuVirtualDisplayClient()
    private val config = VirtualDisplayConfig.fromPhysicalDisplay(service)
    private val lifecycleMutex = Mutex()

    private val _preview = MutableStateFlow<Bitmap?>(null)
    override val preview: StateFlow<Bitmap?> = _preview

    @Volatile
    private var displayId: Int = Display.INVALID_DISPLAY

    @Volatile
    private var imageReader: ImageReader? = null

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
        }
        delay(SURFACE_READY_DELAY_MS)
    }

    override suspend fun stop() {
        lifecycleMutex.withLock {
            val oldId = displayId
            displayId = Display.INVALID_DISPLAY
            val reader = imageReader
            imageReader = null

            if (oldId != Display.INVALID_DISPLAY) {
                stopLaunchedAppOnVirtualDisplay()
                delay(200)
                client.releaseVirtualDisplay(oldId)
            }
            reader?.close()
            client.clear()
        }
    }

    override suspend fun captureScreen(): ScreenCapture {
        hideSoftInput()
        val reader = imageReader ?: error("虚拟屏未启动")

        var image = reader.acquireLatestImage()
        var attempts = 0
        while (image == null && attempts < 12) {
            delay(120)
            image = reader.acquireLatestImage()
            attempts++
        }
        val acquired = image ?: error("无法获取虚拟屏图像")

        return withContext(Dispatchers.Default) {
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

                _preview.value = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)

                ScreenCapture(
                    width = config.width,
                    height = config.height,
                    bitmap = bitmap,
                )
            } finally {
                acquired.close()
            }
        }
    }
    override suspend fun performAction(action: AgentAction): ActionResult {
        val currentDisplay = displayId
        if (currentDisplay == Display.INVALID_DISPLAY) {
            return ActionResult.Failure("虚拟屏未启动")
        }

        val kind = action.kind.lowercase()
        val result =
            try {
                when (kind) {
                    "launch" -> launchApp(action.params["app"]?.toString().orEmpty())
                    "tap" -> tapOrFocus(action)
                    "swipe" -> shellInput(
                        listOf(
                            "input", "-d", "$currentDisplay", "swipe",
                            "${int(action, "startX")}", "${int(action, "startY")}",
                            "${int(action, "endX")}", "${int(action, "endY")}",
                            "${int(action, "durationMs", 300)}",
                        ),
                    )
                    "long press" -> shellInput(
                        listOf(
                            "input", "-d", "$currentDisplay", "swipe",
                            "${int(action, "x")}", "${int(action, "y")}",
                            "${int(action, "x")}", "${int(action, "y")}",
                            "${int(action, "durationMs", 1000)}",
                        ),
                    )
                    "double tap" -> {
                        val first = shellInput(listOf("input", "-d", "$currentDisplay", "tap", "${int(action, "x")}", "${int(action, "y")}"))
                        if (first !is ActionResult.Success) first
                        else shellInput(listOf("input", "-d", "$currentDisplay", "tap", "${int(action, "x")}", "${int(action, "y")}"))
                    }
                    "type" -> typeText(action.params["text"]?.toString().orEmpty(), currentDisplay)
                    "back" -> shellInput(listOf("input", "-d", "$currentDisplay", "keyevent", "4"))
                    "home" -> shellInput(listOf("input", "-d", "$currentDisplay", "keyevent", "3"))
                    "enter", "search", "key" -> {
                        val key = action.params["key"]?.toString()?.lowercase()
                        val isEnter = key == "enter" || kind == "enter" || kind == "search"
                        val code = if (isEnter) client.executeShellCommand(arrayOf("input", "-d", "$currentDisplay", "keyevent", "66")) else -1
                        if (code == 0) ActionResult.Success("已发送回车") else ActionResult.Failure("回车动作失败")
                    }
                    "wait" -> {
                        delay(int(action, "durationMs", 1000).coerceAtLeast(100).toLong())
                        ActionResult.Success("等待完成")
                    }
                    "take_over" -> ActionResult.Success("请求人工接管：${action.params["message"]}")
                    "note", "call_api", "interact" -> ActionResult.Success("忽略辅助动作：${action.kind}")
                    else -> ActionResult.Failure("暂不支持的动作：${action.kind}")
                }
            } catch (error: Exception) {
                ActionResult.Failure(error.message ?: "虚拟屏动作失败", error)
            }

        hideSoftInput()
        if (kind in keyboardTriggerActions) {
            delay(120)
            hideSoftInput()
        }
        return result
    }

    private fun hideSoftInput() {
        val inputMethodManager =
            service.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                ?: return
        runCatching { inputMethodManager.hideSoftInputFromWindow(null, 0) }
        runCatching {
            inputMethodManager.toggleSoftInput(InputMethodManager.HIDE_IMPLICIT_ONLY, 0)
        }
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
            ActionResult.Success("已在虚拟屏启动 $appName")
        } else {
            ActionResult.Failure("在虚拟屏启动 $appName 失败，退出码 $code")
        }
    }

    private suspend fun typeText(text: String, currentDisplay: Int): ActionResult {
        if (text.isEmpty()) return ActionResult.Failure("文本为空")

        val root = rootOnDisplay()
        Log.i(TAG, "typeText display=$currentDisplay text=$text root=${root != null} lastTapPoint=$lastTapPoint")
        val point = lastTapPoint
        val node =
            if (point != null) {
                TextNodeSupport.findTextNodeAt(root, point.first, point.second)
            } else {
                null
            } ?: TextNodeSupport.findFocusedTextNode(root)
                ?: TextNodeSupport.findFirstTextNode(root)
        lastTapPoint = null

        if (node != null) {
            Log.i(TAG, "typeText nodeClass=${node.className} editable=${node.isEditable} text=${node.text}")
            val written = TextNodeSupport.writeText(node, text)
            val verified = written && TextNodeSupport.containsText(node, text)
            if (node !== root) node.recycle()
            root?.recycle()
            if (verified) {
                return ActionResult.Success("已通过无障碍节点写入文本")
            }
        } else {
            root?.recycle()
        }

        val committed = SilentImeController.withSilentIme(service) {
            var ok = false
            repeat(3) {
                if (SilentImeController.commit(text)) {
                    ok = true
                    return@withSilentIme true
                }
                delay(120)
            }
            false
        }

        if (committed) {
            return ActionResult.Success("已通过静默输入法输入文本")
        }

        val pastedByKey = withTemporaryClipboard(text) {
            client.executeShellCommand(
                arrayOf("input", "-d", "$currentDisplay", "keyevent", "279"),
            ) == 0
        }
        return if (pastedByKey) {
            ActionResult.Success("已通过粘贴键输入文本")
        } else {
            ActionResult.Failure("未找到可输入控件")
        }
    }

    private fun tapOrFocus(action: AgentAction): ActionResult {
        val x = int(action, "x")
        val y = int(action, "y")
        lastTapPoint = x to y
        return shellInput(listOf("input", "-d", "$displayId", "tap", "$x", "$y"))
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

    private fun int(action: AgentAction, key: String, default: Int? = null): Int {
        val value = action.params[key]
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: default ?: error("参数不是数字：$key=$value")
            else -> default ?: error("缺少参数：$key")
        }
    }
}
