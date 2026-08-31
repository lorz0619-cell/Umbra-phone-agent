package com.bluewhale.agent.virtualdisplay

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

data class VirtualDisplayConfig(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val density: Float,
) {
    init {
        require(width > 0)
        require(height > 0)
        require(densityDpi > 0)
        require(density > 0f)
    }

    companion object {
        fun fromPhysicalDisplay(context: Context): VirtualDisplayConfig {
            val windowManager =
                context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val density = context.resources.displayMetrics.density
            val densityDpi = context.resources.displayMetrics.densityDpi

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val bounds = windowManager.maximumWindowMetrics.bounds
                return VirtualDisplayConfig(
                    width = bounds.width(),
                    height = bounds.height(),
                    densityDpi = densityDpi,
                    density = density,
                )
            }

            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            return VirtualDisplayConfig(
                width = metrics.widthPixels,
                height = metrics.heightPixels,
                densityDpi = densityDpi,
                density = density,
            )
        }
    }
}