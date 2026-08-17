package com.glasscontrol.dyson

import android.content.Context
import com.glasscontrol.dyson.data.DysonRepositoryImpl
import com.glasscontrol.dyson.data.cloud.DysonCloudApi
import com.glasscontrol.dyson.data.discovery.DysonDiscovery
import com.glasscontrol.dyson.data.local.DysonMqttClient
import com.glasscontrol.dyson.data.store.DeviceConfigStore
import com.glasscontrol.dyson.data.store.SecureCredentialStore
import com.glasscontrol.dyson.data.store.StateCache
import com.glasscontrol.dyson.data.store.WidgetConfigStore
import com.glasscontrol.dyson.domain.DysonRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/**
 * Manual dependency container.
 *
 * The widget, its workers and the activity all run in the same process but not
 * always with an Application-scoped entry point, so everything is created lazily
 * from an application context the first time it is asked for.
 */
object DysonServices {

    private lateinit var appContext: Context

    /** Long-lived scope for the live MQTT session; outlives any single screen. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val deviceConfigStore: DeviceConfigStore by lazy { DeviceConfigStore(appContext) }
    val credentialStore: SecureCredentialStore by lazy { SecureCredentialStore(appContext) }
    val stateCache: StateCache by lazy { StateCache(appContext) }
    val widgetConfigStore: WidgetConfigStore by lazy { WidgetConfigStore(appContext) }
    val cloudApi: DysonCloudApi by lazy { DysonCloudApi() }

    val repository: DysonRepository by lazy {
        DysonRepositoryImpl(
            deviceConfigStore = deviceConfigStore,
            credentialStore = credentialStore,
            stateCache = stateCache,
            discovery = DysonDiscovery(appContext),
            mqttClient = DysonMqttClient(),
            scope = scope,
            onStateChanged = {
                // Any state write, wherever it came from, repaints the home screen.
                com.glasscontrol.dyson.widget.DysonWidgetUpdater.updateAll(appContext)
            },
        )
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Safe to call from a widget or worker that may start the process cold. */
    fun ensureInitialised(context: Context) {
        if (!::appContext.isInitialized) init(context)
    }
}
