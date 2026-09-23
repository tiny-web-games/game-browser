package com.tinywebgames.browser.utils

import android.content.Context

object OrientationManager {
    private const val PREFS_NAME = "game_orientation_prefs"
    private const val KEY_PREFIX = "pref_orientation_"

    /**
     * 获取指定游戏记忆的横竖屏偏好（若未记录过，则返回游戏默认方向）
     */
    fun getPreferredOrientation(context: Context, gameId: String, defaultOrientation: String = "landscape"): String {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getString(KEY_PREFIX + gameId, defaultOrientation) ?: defaultOrientation
    }

    /**
     * 持久化记忆当前游戏的横竖屏偏好
     */
    fun savePreferredOrientation(context: Context, gameId: String, orientation: String) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_PREFIX + gameId, orientation).apply()
    }
}
