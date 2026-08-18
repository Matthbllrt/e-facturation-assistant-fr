package com.glasscontrol.dyson.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.glasscontrol.dyson.core.LogArea
import com.glasscontrol.dyson.core.logW

/** Repaints every placed widget. Safe to call from any coroutine. */
object DysonWidgetUpdater {

    suspend fun updateAll(context: Context) {
        runCatching {
            DysonWidgetClasses.all.forEach { it.updateAll(context) }
        }.onFailure { logW(LogArea.WIDGET_UPDATE, "Widget update failed", it) }
    }

    /** True when the user has at least one widget on a home screen. */
    suspend fun hasWidgets(context: Context): Boolean = runCatching {
        val manager = GlanceAppWidgetManager(context)
        DysonWidgetClasses.classes.any { manager.getGlanceIds(it).isNotEmpty() }
    }.getOrDefault(false)
}
