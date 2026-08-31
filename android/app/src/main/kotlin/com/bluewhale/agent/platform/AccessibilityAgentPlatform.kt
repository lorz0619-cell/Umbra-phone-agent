package com.bluewhale.agent.platform

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.bluewhale.agent.input.SilentImeController
import com.bluewhale.agent.virtualdisplay.ShizukuVirtualDisplayClient
import com.bluewhale.agent.model.ActionResult
import com.bluewhale.agent.model.AgentAction
import com.bluewhale.agent.model.ScreenCapture
import com.bluewhale.agent.model.TargetMode
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "BluewhaleMain"

class AccessibilityAgentPlatform(
    private val service: AccessibilityService,
    private val shizukuClient: ShizukuVirtualDisplayClient = ShizukuVirtualDisplayClient()
) : AgentPlatform {
    override val mode = TargetMode.MAIN_SCREEN

    override fun isAvailable(): Boolean = true

    override suspend fun start() = Unit

    override suspend fun stop() = Unit

    override suspend fun captureScreen(): ScreenCapture =
        suspendCancellableCoroutine { continuation ->
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        val wrapped = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        val bitmap =
                            wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                                ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                        wrapped?.recycle()
                        buffer?.close()
                        continuation.resume(
                            ScreenCapture(
                                width = bitmap.width,
                                height = bitmap.height,
                                bitmap = bitmap,
                            ),
                        )
                    }

                    override fun onFailure(errorCode: Int) {
                        continuation.resumeWithException(
                            IllegalStateException("无障碍截图失败：$errorCode"),
                        )
                    }
                },
            )
        }

    override suspend fun performAction(action: AgentAction): ActionResult {
        return try {
            when (action.kind.lowercase()) {
                "tap" -> {
                    val x = action.params.intValue("x")
                    val y = action.params.intValue("y")
                    dispatchGesture(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 80)
                    ActionResult.Success("点击 ($x,$y)")
                }
                "launch" -> launchApp(action.params["app"]?.toString().orEmpty())
                "type" -> typeText(action.params["text"]?.toString().orEmpty())
                "swipe" -> {
                    val startX = action.params.intValue("startX")
                    val startY = action.params.intValue("startY")
                    val endX = action.params.intValue("endX")
                    val endY = action.params.intValue("endY")
                    dispatchGesture(
                        Path().apply {
                            moveTo(startX.toFloat(), startY.toFloat())
                            lineTo(endX.toFloat(), endY.toFloat())
                        },
                        300,
                    )
                    ActionResult.Success("滑动 ($startX,$startY) -> ($endX,$endY)")
                }
                "back" -> {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    ActionResult.Success("返回")
                }
                "home" -> {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    ActionResult.Success("回到桌面")
                }
                "enter", "search", "key" -> {
                    val key = action.params["key"]?.toString()?.lowercase()
                    val isEnter = key == "enter" || action.kind.equals("enter", true) || action.kind.equals("search", true)
                    val code = if (isEnter) shizukuClient.executeShellCommand(arrayOf("input", "keyevent", "66")) else -1
                    if (code == 0) ActionResult.Success("已发送回车") else ActionResult.Failure("回车动作失败")
                }
                "wait" -> {
                    val duration = action.params.intValue("durationMs").coerceAtLeast(500)
                    delay(duration.toLong())
                    ActionResult.Success("等待 ${duration}ms")
                }
                "take_over" -> ActionResult.Success("请求人工接管：${action.params["message"]}")
                "note", "call_api", "interact" -> ActionResult.Success("忽略辅助动作：${action.kind}")
                else -> ActionResult.Failure("暂不支持的动作：${action.kind}")
            }
        } catch (error: Exception) {
            ActionResult.Failure(error.message ?: "主屏动作失败", error)
        }
    }

    override suspend fun isTextInputFocused(): Boolean {
        val root = activeRoot()
        val node = TextNodeSupport.findFocusedTextNode(root) ?: TextNodeSupport.findFirstTextNode(root)
        if (node !== root) node?.recycle()
        root?.recycle()
        return node != null
    }

    private suspend fun launchApp(appName: String): ActionResult {
        val packageName = AppPackages.resolve(service.packageManager, appName)
            ?: return ActionResult.Failure("未找到应用：$appName")
        val intent =
            service.packageManager.getLaunchIntentForPackage(packageName)
                ?: return ActionResult.Failure("应用不可启动：$packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        service.startActivity(intent)
        return ActionResult.Success("已启动 $appName")
    }

    private fun activeRoot(): AccessibilityNodeInfo? {
        val windows = allWindows()
        Log.i(TAG, "activeRoot rootInActiveWindow=${service.rootInActiveWindow != null} windows=${windows.map { "id=${it.id},type=${it.type},display=${it.displayId},layer=${it.layer},title=${it.title}" }}")
        service.rootInActiveWindow?.let { return it }
        return windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }
            .firstOrNull()
            ?.root
    }

    private fun allWindows(): List<AccessibilityWindowInfo> {
        val legacy = service.windows
        if (!legacy.isNullOrEmpty()) return legacy
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val sparse = service.getWindowsOnAllDisplays()
            val result = mutableListOf<AccessibilityWindowInfo>()
            for (index in 0 until sparse.size()) {
                result += sparse.valueAt(index)
            }
            return result
        }
        return emptyList()
    }

    private suspend fun typeText(text: String): ActionResult {
        if (text.isEmpty()) return ActionResult.Failure("文本为空")

        val root = activeRoot()
        Log.i(TAG, "typeText root=${root != null} rootPackage=${root?.packageName} text=$text")
        val node = TextNodeSupport.findFocusedTextNode(root)
            ?: TextNodeSupport.findFirstTextNode(root)
        if (node != null) {
            Log.i(TAG, "typeText nodeClass=${node.className} editable=${node.isEditable} focused=${node.isFocused} text=${node.text}")
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
            shizukuClient.executeShellCommand(arrayOf("input", "keyevent", "279")) == 0
        }
        if (pastedByKey) {
            return ActionResult.Success("已通过主屏粘贴键输入文本")
        }
        return ActionResult.Failure("未找到可输入控件")
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

    private fun findFocusedEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isFocused) return node
        for (index in 0 until node.childCount) {
            val found = findFocusedEditable(node.getChild(index))
            if (found != null) return found
        }
        return null
    }

    private fun dispatchGesture(path: Path, durationMs: Long): ActionResult {
        val gesture =
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
        return if (service.dispatchGesture(gesture, null, null)) {
            ActionResult.Success("手势已注入")
        } else {
            ActionResult.Failure("手势注入失败")
        }
    }

    private fun Map<String, Any?>.intValue(key: String): Int {
        val value = this[key] ?: error("缺少参数：$key")
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: error("参数不是数字：$key=$value")
            else -> error("参数不是数字：$key=$value")
        }
    }
}

object AppPackages {
    private val aliases =
        mapOf(
            "微信" to "com.tencent.mm",
            "wechat" to "com.tencent.mm",
            "qq" to "com.tencent.mobileqq",
            "微博" to "com.sina.weibo",
            "淘宝" to "com.taobao.taobao",
            "京东" to "com.jingdong.app.mall",
            "拼多多" to "com.xunmeng.pinduoduo",
            "小红书" to "com.xingin.xhs",
            "知乎" to "com.zhihu.android",
            "高德地图" to "com.autonavi.minimap",
            "百度地图" to "com.baidu.BaiduMap",
            "美团" to "com.sankuai.meituan",
            "饿了么" to "me.ele",
            "抖音" to "com.ss.android.ugc.aweme",
            "设置" to "com.android.settings",
            "chrome" to "com.android.chrome",
            "浏览器" to "com.android.chromium",
            "百度" to "com.baidu.searchbox",
        )

    fun resolve(
        packageManager: android.content.pm.PackageManager,
        appName: String,
    ): String? {
        val name = appName.trim()
        if (name.isBlank()) return null

        if ("." in name && "/" !in name) {
            if (packageManager.getLaunchIntentForPackage(name) != null) return name
        }

        val normalized = normalize(name)
        val launchable = launchablePackages(packageManager)
        val byPackageName =
            launchable.firstOrNull { (packageName, _) -> normalize(packageName) == normalized }
        if (byPackageName != null) return byPackageName.first

        val byLabel =
            launchable.firstOrNull { (_, label) -> normalize(label) == normalized }
        if (byLabel != null) return byLabel.first

        val byContains =
            launchable.firstOrNull { (packageName, label) ->
                normalize(label).contains(normalized) ||
                    normalized.contains(normalize(label)) ||
                    normalize(packageName).contains(normalized)
            }
        if (byContains != null) return byContains.first

        val alias = aliases[normalized]
        if (alias != null && packageManager.getLaunchIntentForPackage(alias) != null) return alias

        val aliasEntry =
            aliases.entries.firstOrNull { (key, packageName) ->
                normalize(key).contains(normalized) || normalized.contains(normalize(key))
            }
        if (aliasEntry != null && packageManager.getLaunchIntentForPackage(aliasEntry.value) != null) {
            return aliasEntry.value
        }

        return null
    }

    private fun normalize(value: String): String =
        value
            .lowercase()
            .replace(Regex("""[\s\-_.:/\\（）()【】\[\]]"""), "")

    private fun launchablePackages(
        packageManager: android.content.pm.PackageManager,
    ): List<Pair<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        return try {
            packageManager
                .queryIntentActivities(intent, 0)
                .mapNotNull { resolveInfo ->
                    val packageName = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
                    val label =
                        resolveInfo.loadLabel(packageManager)?.toString()?.trim()
                            ?: packageName
                    packageName to label
                }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
