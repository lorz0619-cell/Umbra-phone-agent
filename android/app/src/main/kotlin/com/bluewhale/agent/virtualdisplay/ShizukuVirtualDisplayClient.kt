package com.bluewhale.agent.virtualdisplay

import android.annotation.SuppressLint
import android.hardware.display.IVirtualDisplayCallback
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import com.bluewhale.agent.BuildConfig
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

@SuppressLint("PrivateApi")
class ShizukuVirtualDisplayClient {
    companion object {
        private const val TAG = "BluewhaleShizuku"
        private val PACKAGE_NAME_CANDIDATES =
            listOf("com.android.shell", null, "moe.shizuku.privileged.api")
    }

    private val displayCallbacks =
        ConcurrentHashMap<Int, IVirtualDisplayCallback>()

    @Volatile
    private var cachedDisplayProxy: Any? = null

    @Volatile
    private var cachedActivityTaskProxy: Any? = null

    fun isAvailable(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean =
        runCatching {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    fun bypassHiddenApis() {
        HiddenApiBypass.addHiddenApiExemptions("")
    }

    fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface,
        flags: Int,
    ): Int {
        return try {
            val proxy = displayManagerProxy()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                createVirtualDisplayApi33(proxy, name, width, height, densityDpi, surface, flags)
            } else {
                createVirtualDisplayLegacy(proxy, name, width, height, densityDpi, surface, flags)
            }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to create virtual display", error)
            -1
        }
    }

    fun releaseVirtualDisplay(displayId: Int) {
        if (displayId < 0) return
        try {
            val proxy = displayManagerProxy()
            val callback = displayCallbacks[displayId]
            if (callback != null) {
                val method =
                    proxy.javaClass.getMethod(
                        "releaseVirtualDisplay",
                        IVirtualDisplayCallback::class.java,
                    )
                method.invoke(proxy, callback)
            }
            displayCallbacks.remove(displayId)
            Log.d(TAG, "Released virtual display $displayId")
        } catch (error: Exception) {
            Log.e(TAG, "Failed to release virtual display $displayId", error)
        }
    }

    fun setVirtualDisplaySurface(displayId: Int, surface: Surface): Boolean {
        if (displayId < 0) return false
        return try {
            val callback = displayCallbacks[displayId] ?: return false
            val proxy = displayManagerProxy()
            val method =
                proxy.javaClass.getMethod(
                    "setVirtualDisplaySurface",
                    IVirtualDisplayCallback::class.java,
                    Surface::class.java,
                )
            method.invoke(proxy, callback, surface)
            Log.d(TAG, "Rebound surface for virtual display $displayId")
            true
        } catch (error: Exception) {
            Log.e(TAG, "Failed to rebind surface for virtual display $displayId", error)
            false
        }
    }

    fun removeRootTasksOnDisplay(displayId: Int): Int {
        if (displayId < 0) return -1
        return try {
            val proxy = activityTaskManagerProxy()
            val getAllMethod =
                proxy.javaClass.getMethod(
                    "getAllRootTaskInfosOnDisplay",
                    Int::class.javaPrimitiveType,
                )
            val rootTaskInfos =
                getAllMethod.invoke(proxy, displayId) as? List<*>
                    ?: emptyList<Any>()
            val removeMethod =
                proxy.javaClass.getMethod(
                    "removeTask",
                    Int::class.javaPrimitiveType,
                )
            var removed = 0
            rootTaskInfos.forEach { info ->
                val taskId =
                    info?.let { target ->
                        runCatching {
                            target.javaClass.getField("taskId").getInt(target)
                        }.getOrDefault(-1)
                    } ?: -1
                if (taskId >= 0 && removeMethod.invoke(proxy, taskId) as? Boolean == true) {
                    removed++
                }
            }
            Log.i(TAG, "Removed $removed root task(s) from virtual display $displayId")
            removed
        } catch (error: Exception) {
            Log.w(TAG, "Failed to remove root tasks from virtual display $displayId", error)
            -1
        }
    }

    /**
     * Move the currently visible root task from a virtual display to the physical display.
     * The operation preserves the task's page state, unlike launching the package again.
     */
    fun moveVisibleRootTaskToDisplay(
        fromDisplayId: Int,
        toDisplayId: Int,
    ): Int {
        if (fromDisplayId < 0 || toDisplayId < 0) return -1
        return try {
            val proxy = activityTaskManagerProxy()
            val getAllMethod =
                proxy.javaClass.getMethod(
                    "getAllRootTaskInfosOnDisplay",
                    Int::class.javaPrimitiveType,
                )
            val rootTaskInfos =
                getAllMethod.invoke(proxy, fromDisplayId) as? List<*>
                    ?: emptyList<Any>()
            val candidates =
                rootTaskInfos.mapNotNull { info ->
                    info?.let { target ->
                        val taskId =
                            runCatching {
                                target.javaClass.getField("taskId").getInt(target)
                            }.getOrDefault(-1)
                        val visible =
                            runCatching {
                                target.javaClass.getField("isVisible").getBoolean(target)
                            }.getOrDefault(false)
                        val topActivity =
                            runCatching {
                                target.javaClass.getField("topActivity").get(target)?.toString()
                            }.getOrNull()
                        RootTaskCandidate(taskId, visible, topActivity)
                    }
                }.filter { it.taskId >= 0 }
            val selected =
                candidates.firstOrNull { it.visible && it.topActivity != null }
                    ?: candidates.firstOrNull { it.topActivity != null }
                    ?: candidates.firstOrNull()
                    ?: return 0
            val moveMethod =
                proxy.javaClass.getMethod(
                    "moveRootTaskToDisplay",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
            moveMethod.invoke(proxy, selected.taskId, toDisplayId)
            Log.i(
                TAG,
                "Moved root task ${selected.taskId} (${selected.topActivity}) " +
                    "from display $fromDisplayId to $toDisplayId",
            )
            1
        } catch (error: Exception) {
            Log.e(
                TAG,
                "Failed to move visible root task from display $fromDisplayId to $toDisplayId",
                error,
            )
            -1
        }
    }

    fun executeShellCommand(command: Array<String>): Int {
        val commandForLog =
            if (BuildConfig.DEBUG) {
                command.joinToString(" ")
            } else {
                "${command.firstOrNull().orEmpty()} <redacted-args:${(command.size - 1).coerceAtLeast(0)}>"
            }
        return try {
            val process = newProcessViaShizuku(command)
            if (!waitForProcess(process, 30, TimeUnit.SECONDS)) {
                process.destroy()
                Log.e(TAG, "Shell command timed out: $commandForLog")
                return -1
            }
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                val error = process.errorStream.bufferedReader().use { it.readText() }
                val safeError = if (BuildConfig.DEBUG) error else "<redacted:${error.length}>"
                Log.w(TAG, "Shell command failed ($exitCode): $commandForLog\n$safeError")
            }
            exitCode
        } catch (error: Exception) {
            Log.e(TAG, "Failed to execute shell command: $commandForLog", error)
            -1
        }
    }

    fun clear() {
        displayCallbacks.clear()
        cachedDisplayProxy = null
        cachedActivityTaskProxy = null
    }

    private fun createVirtualDisplayApi33(
        proxy: Any,
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface,
        flags: Int,
    ): Int {
        val builderClass =
            Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
        val builder =
            builderClass
                .getConstructor(
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                .newInstance(name, width, height, densityDpi)

        builderClass.getMethod("setSurface", Surface::class.java).invoke(builder, surface)
        builderClass.getMethod("setFlags", Int::class.javaPrimitiveType).invoke(builder, flags)

        val config =
            builderClass.getMethod("build").invoke(builder)
                ?: throw IllegalStateException("VirtualDisplayConfig.Builder.build() returned null")

        val callback = newDisplayCallback()
        val configClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
        val projectionClass = Class.forName("android.media.projection.IMediaProjection")
        val method =
            proxy.javaClass.getMethod(
                "createVirtualDisplay",
                configClass,
                IVirtualDisplayCallback::class.java,
                projectionClass,
                String::class.java,
            )

        var lastError: Throwable? = null
        for (packageName in PACKAGE_NAME_CANDIDATES) {
            try {
                val displayId =
                    method.invoke(proxy, config, callback, null, packageName) as Int
                return registerDisplayCallback(displayId, callback)
            } catch (error: InvocationTargetException) {
                if (isPackageUidMismatch(error)) {
                    lastError = error
                    continue
                }
                throw error
            }
        }
        throw lastError ?: IllegalStateException("createVirtualDisplay API33 failed")
    }

    private fun createVirtualDisplayLegacy(
        proxy: Any,
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface,
        flags: Int,
    ): Int {
        val callback = newDisplayCallback()
        val projectionClass = Class.forName("android.media.projection.IMediaProjection")

        return try {
            val method =
                proxy.javaClass.getMethod(
                    "createVirtualDisplay",
                    IVirtualDisplayCallback::class.java,
                    projectionClass,
                    String::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Surface::class.java,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                )
            var lastError: Throwable? = null
            for (packageName in PACKAGE_NAME_CANDIDATES) {
                try {
                    val displayId =
                        method.invoke(
                            proxy,
                            callback,
                            null,
                            packageName,
                            name,
                            width,
                            height,
                            densityDpi,
                            surface,
                            flags,
                            null,
                        ) as Int
                    return registerDisplayCallback(displayId, callback)
                } catch (error: InvocationTargetException) {
                    if (isPackageUidMismatch(error)) {
                        lastError = error
                        continue
                    }
                    throw error
                }
            }
            throw lastError ?: IllegalStateException("createVirtualDisplay legacy failed")
        } catch (error: NoSuchMethodException) {
            createVirtualDisplayLegacyAlt(
                proxy,
                name,
                width,
                height,
                densityDpi,
                surface,
                flags,
                callback,
                projectionClass,
            )
        }
    }

    private fun createVirtualDisplayLegacyAlt(
        proxy: Any,
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface,
        flags: Int,
        callback: IVirtualDisplayCallback,
        projectionClass: Class<*>,
    ): Int {
        val method =
            proxy.javaClass.getMethod(
                "createVirtualDisplay",
                IVirtualDisplayCallback::class.java,
                projectionClass,
                String::class.java,
                Surface::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
            )
        var lastError: Throwable? = null
        for (packageName in PACKAGE_NAME_CANDIDATES) {
            try {
                val displayId =
                    method.invoke(
                        proxy,
                        callback,
                        null,
                        packageName,
                        surface,
                        flags,
                        name,
                    ) as Int
                return registerDisplayCallback(displayId, callback)
            } catch (error: InvocationTargetException) {
                if (isPackageUidMismatch(error)) {
                    lastError = error
                    continue
                }
                throw error
            }
        }
        throw lastError ?: IllegalStateException("createVirtualDisplay legacy-alt failed")
    }

    private fun activityTaskManagerProxy(): Any {
        cachedActivityTaskProxy?.let { return it }
        val binder =
            SystemServiceHelper.getSystemService("activity_task")
                ?: throw IllegalStateException("Cannot obtain activity_task service binder")
        val wrapped = ShizukuBinderWrapper(binder)
        val stubClass = Class.forName("android.app.IActivityTaskManager\$Stub")
        val proxy =
            stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, wrapped)
                ?: throw IllegalStateException("IActivityTaskManager.asInterface returned null")
        cachedActivityTaskProxy = proxy
        return proxy
    }

    private fun displayManagerProxy(): Any {
        cachedDisplayProxy?.let { return it }
        val binder =
            SystemServiceHelper.getSystemService("display")
                ?: throw IllegalStateException("Cannot obtain display service binder")
        val wrapped = ShizukuBinderWrapper(binder)
        val stubClass = Class.forName("android.hardware.display.IDisplayManager\$Stub")
        val proxy =
            stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, wrapped)
                ?: throw IllegalStateException("IDisplayManager.asInterface returned null")
        cachedDisplayProxy = proxy
        return proxy
    }

    private fun newDisplayCallback(): IVirtualDisplayCallback =
        object : IVirtualDisplayCallback.Stub() {
            override fun onPaused() {}
            override fun onResumed() {}
            override fun onStopped() {}
        }

    private fun registerDisplayCallback(
        displayId: Int,
        callback: IVirtualDisplayCallback,
    ): Int {
        if (displayId >= 0) {
            displayCallbacks[displayId] = callback
        }
        Log.d(TAG, "Created virtual display displayId=$displayId")
        return displayId
    }

    private fun isPackageUidMismatch(error: InvocationTargetException): Boolean =
        error.cause?.message?.contains("packageName must match the calling uid") == true

    private fun waitForProcess(
        process: Process,
        timeout: Long,
        unit: TimeUnit,
    ): Boolean {
        val start = System.nanoTime()
        val totalNanos = unit.toNanos(timeout)
        var remaining = totalNanos
        var sleepMs = 10L

        do {
            try {
                process.exitValue()
                return true
            } catch (_: IllegalThreadStateException) {
                // Keep waiting.
            } catch (error: IllegalArgumentException) {
                if (error.message?.contains("process hasn't exited") != true) throw error
            }

            if (remaining > 0) {
                try {
                    Thread.sleep(
                        minOf(TimeUnit.NANOSECONDS.toMillis(remaining) + 1, sleepMs),
                    )
                    sleepMs = minOf(sleepMs * 2, 100L)
                } catch (_: InterruptedException) {
                    return false
                }
            }
            remaining = totalNanos - (System.nanoTime() - start)
        } while (remaining > 0)

        return false
    }

    private fun newProcessViaShizuku(command: Array<String>): Process {
        val shizukuClass = Shizuku::class.java
        val method =
            runCatching {
                shizukuClass.getMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java,
                )
            }.getOrNull()
                ?: shizukuClass.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java,
                )
        method.isAccessible = true
        return method.invoke(null, command, null, null) as Process
    }

    private data class RootTaskCandidate(
        val taskId: Int,
        val visible: Boolean,
        val topActivity: String?,
    )
}
