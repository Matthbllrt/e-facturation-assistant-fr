package com.glasscontrol.dyson.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import com.glasscontrol.dyson.DysonServices
import com.glasscontrol.dyson.data.store.WidgetConfig
import com.glasscontrol.dyson.domain.CapabilityResolver
import com.glasscontrol.dyson.domain.model.DysonFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Loads the state a widget needs.
 *
 * Reading straight from the cache means the widget paints even when the machine
 * is unreachable — it just shows its last known values with an offline badge.
 */
private suspend fun loadUiState(context: Context, glanceId: GlanceId): WidgetUiState {
    DysonServices.ensureInitialised(context)

    val appWidgetId = runCatching {
        GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
    }.getOrDefault(AppWidgetManager.INVALID_APPWIDGET_ID)

    val config = if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
        WidgetConfig()
    } else {
        DysonServices.widgetConfigStore.read(appWidgetId)
    }

    val device = DysonServices.deviceConfigStore.device.first()
    val state = DysonServices.stateCache.read()
    val capabilities = CapabilityResolver.resolve(device?.family ?: DysonFamily.UNSUPPORTED, state)

    return WidgetUiState(
        deviceName = device?.name ?: "Dyson",
        modelName = device?.displayModel ?: "Non configuré",
        state = state,
        capabilities = capabilities,
        config = config,
        configured = device != null,
    )
}

/** 4x4 widget. */
class DysonHeroWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ui = loadUiState(context, id)
        provideContent { HeroWidgetContent(ui) }
    }
}

/** 4x2 widget. */
class DysonCompactWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ui = loadUiState(context, id)
        provideContent { CompactWidgetContent(ui) }
    }
}

class DysonHeroWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = DysonHeroWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        WidgetCleanup.forget(context, appWidgetIds)
    }
}

class DysonCompactWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = DysonCompactWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        WidgetCleanup.forget(context, appWidgetIds)
    }
}

/** Drops per-instance preferences when a widget is removed from the home screen. */
private object WidgetCleanup {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun forget(context: Context, appWidgetIds: IntArray) {
        DysonServices.ensureInitialised(context)
        scope.launch {
            appWidgetIds.forEach { DysonServices.widgetConfigStore.remove(it) }
        }
    }
}
