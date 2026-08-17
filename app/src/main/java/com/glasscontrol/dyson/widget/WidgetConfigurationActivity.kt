package com.glasscontrol.dyson.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glasscontrol.dyson.DysonServices
import com.glasscontrol.dyson.data.store.AppPrefsStore
import com.glasscontrol.dyson.data.store.WidgetConfig
import com.glasscontrol.dyson.data.store.WidgetConfigStore
import com.glasscontrol.dyson.domain.CapabilityResolver
import com.glasscontrol.dyson.domain.model.DysonFamily
import com.glasscontrol.dyson.ui.home.DysonUiState
import com.glasscontrol.dyson.ui.theme.AppTheme
import com.glasscontrol.dyson.ui.theme.DysonGlassTheme
import com.glasscontrol.dyson.ui.theme.LocalGlassColors
import com.glasscontrol.dyson.ui.widgetpreview.WidgetOptionsEditor
import com.glasscontrol.dyson.ui.widgetpreview.WidgetPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Shown by the launcher when a widget is dropped on the home screen.
 *
 * The activity starts with RESULT_CANCELED so backing out leaves no orphan
 * widget, and only confirms once the user taps through.
 */
class WidgetConfigurationActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        DysonServices.ensureInitialised(applicationContext)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        setResult(RESULT_CANCELED, resultIntent())
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val prefsStore = AppPrefsStore(applicationContext)

        setContent {
            val theme by prefsStore.prefs
                .map { it.theme }
                .collectAsStateWithLifecycle(initialValue = AppTheme.SYSTEM)

            DysonGlassTheme(appTheme = theme) {
                ConfigurationContent(
                    appWidgetId = appWidgetId,
                    onConfirm = ::confirm,
                )
            }
        }
    }

    private fun confirm() {
        setResult(RESULT_OK, resultIntent())
        finish()
    }

    private fun resultIntent() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
}

@Composable
private fun ConfigurationContent(appWidgetId: Int, onConfirm: () -> Unit) {
    val glass = LocalGlassColors.current
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val systemDark = isSystemInDarkTheme()

    var config by remember { mutableStateOf(WidgetConfig()) }

    val ui by remember {
        combine(
            DysonServices.repository.device,
            DysonServices.repository.state,
        ) { device, state ->
            DysonUiState(
                device = device,
                state = state,
                capabilities = CapabilityResolver.resolve(
                    device?.family ?: DysonFamily.UNSUPPORTED,
                    state,
                ),
            )
        }
    }.collectAsStateWithLifecycle(initialValue = DysonUiState())

    // New widgets start from the defaults chosen in the app's Widget tab.
    LaunchedEffect(appWidgetId) {
        val defaults = DysonServices.widgetConfigStore.read(WidgetConfigStore.DEFAULTS_ID)
        config = DysonServices.widgetConfigStore.read(appWidgetId)
            .takeIf { it != WidgetConfig() }
            ?: defaults
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(glass.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = "Configurer le widget",
                style = MaterialTheme.typography.headlineMedium,
                color = glass.onSurface,
                modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
            )
            Text(
                text = ui.device?.name ?: "Aucun Dyson configuré — ouvrez l'application d'abord.",
                style = MaterialTheme.typography.labelSmall,
                color = glass.onSurfaceMuted,
                modifier = Modifier.padding(bottom = 18.dp),
            )

            WidgetPreview(
                config = config,
                state = ui.state,
                capabilities = ui.capabilities,
                deviceName = ui.device?.name ?: "Dyson",
                modelName = ui.device?.displayModel ?: "Non configuré",
                hero = true,
                systemDark = systemDark,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(330.dp),
            )

            Column(modifier = Modifier.padding(top = 20.dp)) {
                WidgetOptionsEditor(
                    config = config,
                    capabilities = ui.capabilities,
                    onConfigChange = { config = it },
                )
            }

            Button(
                onClick = {
                    scope.launch {
                        DysonServices.widgetConfigStore.save(
                            appWidgetId,
                            config.copy(serial = ui.device?.serial),
                        )
                        DysonWidgetUpdater.updateAll(context)
                        onConfirm()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 28.dp)
                    .height(54.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = glass.accent.copy(alpha = if (glass.dark) 0.22f else 0.14f),
                    contentColor = glass.accent,
                ),
            ) {
                Text("AJOUTER LE WIDGET", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
