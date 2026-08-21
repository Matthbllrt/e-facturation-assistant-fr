package app.mosaic.privatevault.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mosaic.privatevault.R
import app.mosaic.privatevault.domain.model.SyncMode
import app.mosaic.privatevault.sync.notification.DiscoveryFeed
import app.mosaic.privatevault.sync.notification.MosaicNotificationListener
import app.mosaic.privatevault.ui.common.MosaicIntents
import app.mosaic.privatevault.ui.common.MosaicMark

/**
 * Five short steps: mode, permission, person, alias, done.
 *
 * Each permission is requested at the step that needs it and nowhere earlier,
 * and the notification-access step explains in plain French why Android is
 * about to show an alarming-sounding dialog.
 */
@Composable
fun OnboardingScreen(
    state: OnboardingState,
    onModeSelected: (SyncMode) -> Unit,
    onTokenEntered: (String) -> Unit,
    onTargetSelected: (displayName: String, handle: String?, threadKey: String?) -> Unit,
    onAliasChosen: (String) -> Unit,
    onFinish: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        MosaicMark(modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(24.dp))

        when (state.step) {
            OnboardingStep.MODE -> ModeStep(onModeSelected)
            OnboardingStep.PERMISSION -> PermissionStep(
                mode = state.mode,
                granted = state.permissionGranted,
                onOpenNotificationSettings = onOpenNotificationSettings,
                onTokenEntered = onTokenEntered,
                onContinue = onFinish,
            )

            OnboardingStep.TARGET -> TargetStep(state.mode, onTargetSelected)
            OnboardingStep.ALIAS -> AliasStep(onAliasChosen)
            OnboardingStep.DONE -> DoneStep(onFinish)
        }
    }
}

enum class OnboardingStep { MODE, PERMISSION, TARGET, ALIAS, DONE }

data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.MODE,
    val mode: SyncMode = SyncMode.UNSET,
    val permissionGranted: Boolean = false,
)

@Composable
private fun ModeStep(onModeSelected: (SyncMode) -> Unit) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Text(
            text = stringResource(R.string.onboarding_mode_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(20.dp))

        ModeCard(
            title = stringResource(R.string.sync_mode_notifications),
            body = stringResource(R.string.onboarding_mode_notifications_body),
            onClick = { onModeSelected(SyncMode.ANDROID_NOTIFICATIONS) },
        )
        Spacer(Modifier.height(12.dp))
        ModeCard(
            title = stringResource(R.string.sync_mode_api),
            body = stringResource(R.string.onboarding_mode_api_body),
            onClick = { onModeSelected(SyncMode.OFFICIAL_API) },
        )
    }
}

@Composable
private fun ModeCard(title: String, body: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionStep(
    mode: SyncMode,
    granted: Boolean,
    onOpenNotificationSettings: () -> Unit,
    onTokenEntered: (String) -> Unit,
    onContinue: () -> Unit,
) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        if (mode == SyncMode.ANDROID_NOTIFICATIONS) {
            Text(
                text = stringResource(R.string.onboarding_permission_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.onboarding_permission_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onOpenNotificationSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_grant_notification_access))
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onContinue, enabled = granted, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_continue))
            }
        } else {
            var token by remember { mutableStateOf("") }
            Text(
                text = stringResource(R.string.onboarding_token_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.onboarding_token_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it.trim() },
                label = { Text(stringResource(R.string.onboarding_token_label)) },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onTokenEntered(token) },
                enabled = token.length > 20,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_continue))
            }
        }
    }
}

@Composable
private fun TargetStep(
    mode: SyncMode,
    onTargetSelected: (String, String?, String?) -> Unit,
) {
    val observed by DiscoveryFeed.observed.collectAsStateWithLifecycle()
    var manualName by remember { mutableStateOf("") }
    var manualHandle by remember { mutableStateOf("") }

    DisposableEffect(mode) {
        // The chooser only listens while this step is on screen.
        if (mode == SyncMode.ANDROID_NOTIFICATIONS) DiscoveryFeed.start()
        onDispose { DiscoveryFeed.stop() }
    }

    Column {
        Text(
            text = stringResource(R.string.onboarding_target_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(
                if (mode == SyncMode.ANDROID_NOTIFICATIONS) {
                    R.string.onboarding_target_body_notifications
                } else {
                    R.string.onboarding_target_body_api
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        if (observed.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(200.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(observed) { candidate ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            onTargetSelected(
                                candidate.displayName,
                                null,
                                candidate.conversationKey,
                            )
                        },
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = candidate.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        OutlinedTextField(
            value = manualName,
            onValueChange = { manualName = it },
            label = { Text(stringResource(R.string.onboarding_target_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = manualHandle,
            onValueChange = { manualHandle = it.removePrefix("@").trim() },
            label = { Text(stringResource(R.string.onboarding_target_handle)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                onTargetSelected(
                    manualName.ifBlank { manualHandle },
                    manualHandle.ifBlank { null },
                    null,
                )
            },
            enabled = manualName.isNotBlank() || manualHandle.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_continue))
        }
    }
}

@Composable
private fun AliasStep(onAliasChosen: (String) -> Unit) {
    var alias by remember { mutableStateOf(app.mosaic.privatevault.domain.model.MosaicSettings.DEFAULT_ALIAS) }
    Column {
        Text(
            text = stringResource(R.string.onboarding_alias_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_alias_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = alias,
            onValueChange = { alias = it.take(32) },
            label = { Text(stringResource(R.string.settings_alias)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onAliasChosen(alias) },
            enabled = alias.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_continue))
        }
    }
}

@Composable
private fun DoneStep(onFinish: () -> Unit) {
    Column {
        Text(
            text = stringResource(R.string.onboarding_done_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.onboarding_done_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_done))
        }
    }
}

/** Convenience used by the host so the step screen stays free of Android calls. */
fun notificationPermissionGranted(context: android.content.Context): Boolean =
    MosaicNotificationListener.isPermissionGranted(context)

fun openNotificationSettings(context: android.content.Context) {
    MosaicIntents.openNotificationAccessSettings(context)
}
