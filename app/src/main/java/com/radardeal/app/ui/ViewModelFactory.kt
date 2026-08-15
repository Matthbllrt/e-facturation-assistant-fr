package com.radardeal.app.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.radardeal.app.AppGraph
import com.radardeal.app.RadarDealApp

/**
 * Builds a ViewModel from the app graph without a DI framework.
 *
 * Keeping this one helper means every screen gets its dependencies the same way, and the
 * whole wiring stays inspectable in two files ([AppGraph] and this one).
 */
@Composable
inline fun <reified VM : ViewModel> radarViewModel(
    key: String? = null,
    crossinline factory: (AppGraph) -> VM,
): VM = viewModel(
    key = key,
    factory = viewModelFactory {
        initializer {
            val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                as Application
            factory(RadarDealApp.graph(application))
        }
    },
)
