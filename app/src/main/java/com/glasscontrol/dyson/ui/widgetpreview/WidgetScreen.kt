package com.glasscontrol.dyson.ui.widgetpreview

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glasscontrol.dyson.DysonServices
import com.glasscontrol.dyson.data.store.WidgetConfig
import com.glasscontrol.dyson.data.store.WidgetConfigStore
import com.glasscontrol.dyson.ui.home.DysonViewModel
import com.glasscontrol.dyson.ui.theme.LocalGlassColors
import com.glasscontrol.dyson.widget.WidgetUiState
import com.glasscontrol.dyson.widget.DysonCompactWidgetReceiver
import com.glasscontrol.dyson.widget.DysonHeroWidgetReceiver
import kotlinx.coroutines.launch

/**
 * Live preview of the home-screen widget, plus the defaults new widgets start from.
 *
 * The preview binds to the same state the widget reads, so toggling a control on
 * Home is visible here immediately.
 */
@Composable
fun WidgetScreen(viewModel: DysonViewModel) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val glass = LocalGlassColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val systemDark = isSystemInDarkTheme()

    var config by remember { mutableStateOf(WidgetConfig()) }

    LaunchedEffect(Unit) {
        config = DysonServices.widgetConfigStore.read(WidgetConfigStore.DEFAULTS_ID)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Text(
            text = "Widget",
            style = MaterialTheme.typography.headlineMedium,
            color = glass.onSurface,
            modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
        )
        Text(
            text = "Aperçu en temps réel. Ces réglages servent de valeurs par défaut " +
                "lorsque vous ajoutez un nouveau widget.",
            style = MaterialTheme.typography.labelSmall,
            color = glass.onSurfaceMuted,
            modifier = Modifier.padding(bottom = 18.dp),
        )

        val previewState = WidgetUiState.from(ui.device, ui.state, ui.capabilities, config)

        WidgetPreview(
            ui = previewState,
            state = ui.state,
            large = true,
            systemDark = systemDark,
            modifier = Modifier
                .fillMaxWidth()
                .height(196.dp),
        )

        WidgetPreview(
            ui = previewState,
            state = ui.state,
            large = false,
            systemDark = systemDark,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .height(126.dp),
        )

        Column(modifier = Modifier.padding(top = 20.dp)) {
            WidgetOptionsEditor(
                config = config,
                deviceName = ui.device?.name ?: "Aucun appareil configuré",
                onConfigChange = { updated ->
                    config = updated
                    scope.launch {
                        DysonServices.widgetConfigStore.save(WidgetConfigStore.DEFAULTS_ID, updated)
                    }
                },
            )
        }

        // Android 8+ lets an app ask the launcher to place a widget directly.
        val manager = AppWidgetManager.getInstance(context)
        if (manager.isRequestPinAppWidgetSupported) {
            TextButton(
                onClick = {
                    manager.requestPinAppWidget(
                        ComponentName(context, DysonHeroWidgetReceiver::class.java),
                        null,
                        null,
                    )
                },
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text("Ajouter le widget 4x4 à l'écran d'accueil", color = glass.accent)
            }
            TextButton(
                onClick = {
                    manager.requestPinAppWidget(
                        ComponentName(context, DysonCompactWidgetReceiver::class.java),
                        null,
                        null,
                    )
                },
            ) {
                Text("Ajouter le widget 4x2", color = glass.accent)
            }
        } else {
            Text(
                text = "Appui long sur l'écran d'accueil → Widgets → Dyson Glass.",
                style = MaterialTheme.typography.labelSmall,
                color = glass.onSurfaceMuted,
                modifier = Modifier.padding(top = 14.dp),
            )
        }

        Column(modifier = Modifier.height(28.dp)) {}
    }
}
