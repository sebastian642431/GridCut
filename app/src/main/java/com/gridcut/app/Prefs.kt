package com.gridcut.app

import android.content.Context

data class Prefs(
    val columns: Int = 3,
    val mode: CutMode = CutMode.Grid,
    val lossless: Boolean = true,
    val numbers: Boolean = true,
    val openAfterCut: Boolean = true,
    val gifAsVideo: Boolean = true,
)

class PrefsStore(context: Context) {
    private val prefs = context.getSharedPreferences("gridcut", Context.MODE_PRIVATE)

    fun load() = Prefs(
        columns = prefs.getInt("columns", 3).coerceIn(1, MAX_COLUMNS),
        mode = runCatching { CutMode.valueOf(prefs.getString("mode", null) ?: "") }.getOrDefault(CutMode.Grid),
        lossless = prefs.getBoolean("lossless", true),
        numbers = prefs.getBoolean("numbers", true),
        openAfterCut = prefs.getBoolean("openAfterCut", true),
        gifAsVideo = prefs.getBoolean("gifAsVideo", true),
    )

    fun save(value: Prefs): Prefs {
        prefs.edit()
            .putInt("columns", value.columns)
            .putString("mode", value.mode.name)
            .putBoolean("lossless", value.lossless)
            .putBoolean("numbers", value.numbers)
            .putBoolean("openAfterCut", value.openAfterCut)
            .putBoolean("gifAsVideo", value.gifAsVideo)
            .apply()
        return value
    }
}
