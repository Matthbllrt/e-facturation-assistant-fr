package com.glasscontrol.dyson.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import com.glasscontrol.dyson.DysonServices
import com.glasscontrol.dyson.data.store.WidgetConfig
import com.glasscontrol.dyson.data.store.WidgetTheme
import com.glasscontrol.dyson.domain.CapabilityResolver
import com.glasscontrol.dyson.domain.model.DysonFamily
import com.glasscontrol.dyson.widget.design.GlassPalette
import com.glasscontrol.dyson.widget.ui.DysonWidgetBody
import com.glasscontrol.dyson.widget.ui.WidgetMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The sizes the widget is laid out for.
 *
 * With [SizeMode.Responsive] Glance builds one layout per entry and hands them
 * all to the launcher, which picks as the user resizes. That means dragging the
 * handles in One UI switches compact to large instantly, with no round trip
 * through our process.
 */
private val COMPACT_SIZE = DpSize(260.dp, 116.dp)
private val LARGE_SIZE = DpSize(260.dp, 186.dp)
private val TALL_SIZE = DpSize(260.dp, 260.dp)

private val SUPPORTED_SIZES = setOf(COMPACT_SIZE, LARGE_SIZE, TALL_SIZE)

/** Above this height the large composition is used. */
private val LARGE_BREAKPOINT = 150.dp

/**
 * Loads what one widget instance needs.
 *
 * Reading from the cache means the widget paints even when the machine cannot be
 * reached; it simply shows the last known values with an offline badge.
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

    return WidgetUiState.from(device, state, capabilities, config)
}

private fun paletteFor(context: Context, config: WidgetConfig): GlassPalette {
    val dark = when (config.theme) {
        WidgetTheme.DARK -> true
        WidgetTheme.LIGHT -> false
        WidgetTheme.AUTO ->
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }
    return GlassPalette.of(dark)
}

/**
 * One widget, two compositions.
 *
 * Both receivers below share this: they differ only in the size the launcher
 * gives them when first dropped, so the picker offers a compact and a large
 * entry while either can still be resized into the other.
 */
private abstract class DysonResponsiveWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(SUPPORTED_SIZES)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ui = loadUiState(context, id)
        provideContent {
            val size = LocalSize.current
            val palette = paletteFor(LocalContext.current, ui.config)
            val metrics = when {
                size.height < LARGE_BREAKPOINT -> WidgetMetrics.Compact
                else -> WidgetMetrics.Large
            }
            DysonWidgetBody(ui, palette, metrics)
        }
    }
}

/** Drops at roughly 4x3 and can be shrunk to compact. */
private class DysonLargeWidget : DysonResponsiveWidget()

/** Drops at roughly 4x2 and can be grown to large. */
private class DysonCompactWidget : DysonResponsiveWidget()

class DysonHeroWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = DysonLargeWidget()

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

/** Everything the updater needs to repaint every placed instance. */
internal object DysonWidgetClasses {
    val all: List<GlanceAppWidget> get() = listOf(DysonLargeWidget(), DysonCompactWidget())
    val classes: List<Class<out GlanceAppWidget>>
        get() = listOf(DysonLargeWidget::class.java, DysonCompactWidget::class.java)
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
