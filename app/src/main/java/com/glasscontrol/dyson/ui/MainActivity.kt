package com.glasscontrol.dyson.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glasscontrol.dyson.DysonServices
import com.glasscontrol.dyson.R
import com.glasscontrol.dyson.data.store.AppPrefsStore
import com.glasscontrol.dyson.ui.device.DeviceScreen
import com.glasscontrol.dyson.ui.home.DysonViewModel
import com.glasscontrol.dyson.ui.home.HomeScreen
import com.glasscontrol.dyson.ui.onboarding.OnboardingScreen
import com.glasscontrol.dyson.ui.settings.SettingsScreen
import com.glasscontrol.dyson.ui.settings.SettingsViewModel
import com.glasscontrol.dyson.ui.theme.AppTheme
import com.glasscontrol.dyson.ui.theme.DysonGlassTheme
import com.glasscontrol.dyson.ui.theme.LocalGlassColors
import com.glasscontrol.dyson.ui.widgetpreview.WidgetScreen
import kotlinx.coroutines.flow.map

private enum class Tab(val label: String, val iconRes: Int) {
    HOME("Home", R.drawable.ic_power),
    WIDGET("Widget", R.drawable.ic_wind),
    DEVICE("Device", R.drawable.ic_oscillation),
    SETTINGS("Settings", R.drawable.ic_settings),
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        DysonServices.ensureInitialised(applicationContext)

        val prefsStore = AppPrefsStore(applicationContext)

        setContent {
            // Remembered so the derived flow is built once, not per recomposition.
            val themeFlow = remember(prefsStore) { prefsStore.prefs.map { it.theme } }
            val theme by themeFlow.collectAsStateWithLifecycle(initialValue = AppTheme.SYSTEM)

            DysonGlassTheme(appTheme = theme) {
                DysonApp(prefsStore)
            }
        }
    }
}

@Composable
private fun DysonApp(prefsStore: AppPrefsStore) {
    val glass = LocalGlassColors.current
    val dysonViewModel: DysonViewModel = viewModel()
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(prefsStore) as T
        }
    )

    val ui by dysonViewModel.uiState.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(Tab.HOME) }
    // Onboarding is shown until a device exists, and again if one is removed.
    var showOnboarding by remember { mutableStateOf(false) }

    LaunchedEffect(ui.configured) {
        showOnboarding = !ui.configured
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(glass.background),
    ) {
        if (showOnboarding) {
            Box(modifier = Modifier.statusBarsPadding().navigationBarsPadding()) {
                OnboardingScreen(onFinished = { showOnboarding = false })
            }
            return@Box
        }

        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Box(modifier = Modifier.weight(1f)) {
                when (tab) {
                    Tab.HOME -> HomeScreen(dysonViewModel)
                    Tab.WIDGET -> WidgetScreen(dysonViewModel)
                    Tab.DEVICE -> DeviceScreen(dysonViewModel)
                    Tab.SETTINGS -> SettingsScreen(
                        viewModel = settingsViewModel,
                        onDeviceForgotten = {
                            tab = Tab.HOME
                            showOnboarding = true
                        },
                    )
                }
            }

            NavigationBar(
                containerColor = Color.Transparent,
                modifier = Modifier.navigationBarsPadding(),
            ) {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = {
                            Icon(
                                painter = painterResource(entry.iconRes),
                                contentDescription = entry.label,
                                modifier = Modifier.padding(2.dp),
                            )
                        },
                        label = { Text(entry.label, style = MaterialTheme.typography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = glass.accent,
                            selectedTextColor = glass.accent,
                            indicatorColor = glass.accentSoft,
                            unselectedIconColor = glass.onSurfaceMuted,
                            unselectedTextColor = glass.onSurfaceMuted,
                        ),
                    )
                }
            }
        }
    }
}
