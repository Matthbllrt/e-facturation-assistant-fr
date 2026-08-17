package com.glasscontrol.dyson.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.glasscontrol.dyson.core.logW

/** Repaints every placed widget. Safe to call from any coroutine. */
object DysonWidgetUpdater {

    suspend fun updateAll(context: Context) {
        runCatching {
            DysonHeroWidget().updateAll(context)
            DysonCompactWidget().updateAll(context)
        }.onFailure { logW("Widget update failed", it) }
    }

    /** True when the user has at least one widget on a home screen. */
    suspend fun hasWidgets(context: Context): Boolean = runCatching {
        val manager = GlanceAppWidgetManager(context)
        manager.getGlanceIds(DysonHeroWidget::class.java).isNotEmpty() ||
            manager.getGlanceIds(DysonCompactWidget::class.java).isNotEmpty()
    }.getOrDefault(false)
}
