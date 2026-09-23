package com.tinywebgames.browser.utils

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object FullscreenHelper {

    /**
     * 为 Activity 窗口启用深度沉浸式全屏模式 (Sticky Immersive Mode)
     * 1. 隐藏系统状态栏与虚拟导航栏
     * 2. 边缘手势仅呼出瞬时半透明系统条，不推挤游戏网页画面
     * 3. 贯穿刘海屏/挖孔屏区域（Display Cutout）
     */
    fun enableImmersiveFullscreen(window: Window) {
        // 让内容延伸到系统栏底层
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.let {
            // 隐藏状态栏和导航栏
            it.hide(WindowInsetsCompat.Type.systemBars())
            // 黏性沉浸模式：边缘滑入只出现短暂的半透明条，几秒后自动淡出
            it.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // 适配刘海屏/挖孔屏短边延伸（Android 9.0+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val params = window.attributes
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = params
        }

        // 针对 Android 8.0 及以下提供兼容性 flag
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
    }

    /**
     * 保持屏幕常亮，防止游玩过程中自动锁屏/黑屏
     */
    fun setKeepScreenOn(window: Window, keepOn: Boolean) {
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
