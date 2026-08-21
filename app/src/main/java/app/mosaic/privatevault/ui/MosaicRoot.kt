package app.mosaic.privatevault.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mosaic.privatevault.MosaicGraph
import app.mosaic.privatevault.domain.alias.Alias
import app.mosaic.privatevault.domain.model.MosaicSettings
import app.mosaic.privatevault.domain.model.SyncMode
import app.mosaic.privatevault.domain.model.TargetIdentity
import app.mosaic.privatevault.sync.work.ApiSyncWorker
import app.mosaic.privatevault.ui.conversation.ConversationScreen
import app.mosaic.privatevault.ui.lock.LockScreen
import app.mosaic.privatevault.ui.onboarding.OnboardingScreen
import app.mosaic.privatevault.ui.onboarding.OnboardingState
import app.mosaic.privatevault.ui.onboarding.OnboardingStep
import app.mosaic.privatevault.ui.onboarding.notificationPermissionGranted
import app.mosaic.privatevault.ui.onboarding.openNotificationSettings
import app.mosaic.privatevault.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

/**
 * The whole navigation graph.
 *
 * Four destinations, chosen by state rather than by a nav library: locked,
 * setup, conversation, settings. Locked always wins, from anywhere.
 */
@Composable
fun MosaicRoot(
    activity: FragmentActivity,
    locked: Boolean,
    onScreenshotProtectionChanged: (Boolean) -> Unit,
) {
    val settings by MosaicGraph.settings.settings
        .collectAsStateWithLifecycle(initialValue = MosaicSettings())
    var showSettings by remember { mutableStateOf(false) }
    var onboarding by remember { mutableStateOf(OnboardingState()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(settings.screenshotProtection) {
        onScreenshotProtectionChanged(settings.screenshotProtection)
    }

    // Any lock event drops the user out of settings, so the screen behind the
    // lock is never the one showing the real identity.
    LaunchedEffect(locked) {
        if (locked) showSettings = false
    }

    when {
        locked -> LockScreen(
            activity = activity,
            onUnlocked = { MosaicGraph.appLock.unlock() },
        )

        !settings.onboardingComplete -> OnboardingScreen(
            state = onboarding.copy(
                permissionGranted = notificationPermissionGranted(activity),
            ),
            onModeSelected = { mode ->
                scope.launch { MosaicGraph.settings.setSyncMode(mode) }
                onboarding = onboarding.copy(mode = mode, step = OnboardingStep.PERMISSION)
            },
            onTokenEntered = { token ->
                MosaicGraph.api.storeToken(token, expiresInSeconds = null)
                ApiSyncWorker.enable(activity)
                onboarding = onboarding.copy(step = OnboardingStep.TARGET)
            },
            onTargetSelected = { displayName, handle, threadKey ->
                MosaicGraph.target.save(
                    TargetIdentity(
                        handle = handle,
                        displayName = displayName,
                        threadKey = threadKey,
                    ),
                )
                onboarding = onboarding.copy(step = OnboardingStep.ALIAS)
            },
            onAliasChosen = { alias ->
                scope.launch {
                    MosaicGraph.settings.setAlias(alias)
                    MosaicGraph.settings.setAvatarSeed(Alias.avatarSeed(Alias.sanitize(alias)))
                }
                onboarding = onboarding.copy(step = OnboardingStep.DONE)
            },
            onFinish = {
                when (onboarding.step) {
                    OnboardingStep.PERMISSION ->
                        onboarding = onboarding.copy(step = OnboardingStep.TARGET)

                    OnboardingStep.DONE -> scope.launch {
                        MosaicGraph.settings.setOnboardingComplete(true)
                        if (onboarding.mode == SyncMode.OFFICIAL_API) {
                            ApiSyncWorker.enable(activity)
                        }
                    }

                    else -> Unit
                }
            },
            onOpenNotificationSettings = { openNotificationSettings(activity) },
        )

        showSettings -> SettingsScreen(
            activity = activity,
            onBack = { showSettings = false },
            onReset = {
                showSettings = false
                MosaicGraph.appLock.lock()
            },
        )

        else -> ConversationScreen(
            onOpenSettings = { showSettings = true },
            onEraseRequested = { showSettings = true },
        )
    }
}
