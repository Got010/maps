package app.southbound.core.map

import android.content.Context
import androidx.annotation.ColorInt
import java.io.File

internal object BasemapStyle {
    /** Matches the background layer of each style, shown while tiles load. */
    @ColorInt
    fun loadingColor(darkTheme: Boolean): Int =
        if (darkTheme) 0xFF1C1F24.toInt() else 0xFFF5F3EF.toInt()

    /** The style for the installed basemaps, as (region, file) pairs. */
    fun load(context: Context, darkTheme: Boolean, basemaps: List<Pair<String, File>>): String {
        val asset = if (darkTheme) "dark.json" else "light.json"
        val template = context.assets.open(asset).bufferedReader().use { it.readText() }
        return StyleTemplate.resolve(template, basemaps)
    }
}
