package com.bluewhale.agent.platform

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager

data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val device: String,
    val sdk: Int,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val rotation: Int,
    val insetLeft: Int,
    val insetTop: Int,
    val insetRight: Int,
    val insetBottom: Int,
) {
    fun promptText(): String =
        "设备=${manufacturer} ${model}(${device}) Android SDK ${sdk}；" +
            "物理屏=${width}x${height} densityDpi=${densityDpi} rotation=${rotation}；" +
            "系统安全边距=[${insetLeft},${insetTop},${insetRight},${insetBottom}]"

    companion object {
        fun read(context: Context): DeviceProfile {
            // AccessibilityService and other background contexts are not associated with a
            // display on recent Android versions. Create a display-bound context before using
            // WindowManager; accessing context.display directly throws on Android 16.
            val displayManager = context.getSystemService(DisplayManager::class.java)
            val display =
                requireNotNull(displayManager.getDisplay(Display.DEFAULT_DISPLAY)) {
                    "Default display is unavailable"
                }
            val displayContext = context.createDisplayContext(display)
            val windowManager =
                displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = windowManager.maximumWindowMetrics
            val bounds = metrics.bounds
            val insets =
                metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
            return DeviceProfile(
                manufacturer = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
                device = Build.DEVICE.orEmpty(),
                sdk = Build.VERSION.SDK_INT,
                width = bounds.width(),
                height = bounds.height(),
                densityDpi = displayContext.resources.displayMetrics.densityDpi,
                rotation = display.rotation,
                insetLeft = insets.left,
                insetTop = insets.top,
                insetRight = insets.right,
                insetBottom = insets.bottom,
            )
        }
    }
}
