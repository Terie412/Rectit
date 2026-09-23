package com.example.explaindot.overlay

import android.content.Context

/**
 * 记住圆点上次被拖到哪儿了。
 *
 * MVP 阶段用 SharedPreferences 就够，不值得为两个整数上 DataStore。
 * 等以后要存阅读历史、术语本之类的东西再换。
 */
class DotPositionStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 返回 (x, y)，窗口坐标（左上角）。从没存过就返回 (-1, -1)，由调用方决定默认落点。 */
    fun load(): Pair<Int, Int> =
        prefs.getInt(KEY_X, UNSET) to prefs.getInt(KEY_Y, UNSET)

    fun save(x: Int, y: Int) {
        prefs.edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()
    }

    private companion object {
        const val PREFS_NAME = "overlay"
        const val KEY_X = "dot_x"
        const val KEY_Y = "dot_y"
        const val UNSET = -1
    }
}
