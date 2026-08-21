package app.mosaic.privatevault.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mosaic.privatevault.MosaicGraph
import app.mosaic.privatevault.R
import app.mosaic.privatevault.domain.alias.Alias
import app.mosaic.privatevault.domain.model.AutoLockDelay
import app.mosaic.privatevault.domain.model.Cooldown
import app.mosaic.privatevault.domain.model.NotificationPolicy
import app.mosaic.privatevault.domain.model.SyncMode
import app.mosaic.privatevault.security.BiometricGate
import app.mosaic.privatevault.sync.notification.MosaicNotificationListener
import app.mosaic.privatevault.ui.common.MosaicIntents
import kotlinx.coroutines.launch

/**
 * Conversation settings.
 *
 * Everything reversible lives at the top; the two irreversible actions — erase
 * and full reset — are at the bottom, behind a confirmation and a fresh
 * authentication.
 */
@Composable
fun SettingsScreen(
    activity: FragmentActivity,
    onBack: () -> Unit,
    onReset: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val revealed by viewModel.revealedIdentity.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var aliasDraft by remember(settings.alias) { mutableStateOf(settings.alias) }
    var showEraseDialog by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showCustomCooldown by remember { mutableStateOf(false) }
    var pendingExportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            pendingExportUri = uri
            showExportDialog = true
        }
    }

    LaunchedEffect(message) {
        message?.let {
            status = it
            viewModel.consumeMessage()
        }
    }

    // The real identity is never left on screen: leaving the screen hides it.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { viewModel.hideIdentity() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Section(stringResource(R.string.settings_section_identity)) {
            OutlinedTextField(
                value = aliasDraft,
                onValueChange = { aliasDraft = it.take(Alias.MAX_LENGTH) },
                label = { Text(stringResource(R.string.settings_alias)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.setAlias(aliasDraft) },
                    enabled = Alias.isValid(aliasDraft) && aliasDraft != settings.alias,
                ) {
                    Text(stringResource(R.string.action_save))
                }
                TextButton(onClick = { aliasDraft = settings.alias }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }

            Spacer(Modifier.height(12.dp))
            if (revealed == null) {
                TextButton(
                    onClick = {
                        scope.launch {
                            // A second, independent authentication: having the
                            // vault open is not the same as being allowed to see
                            // who the alias hides.
                            val gate = MosaicGraph.biometricGate
                            val outcome = if (
                                gate.availability(activity) == BiometricGate.Availability.AVAILABLE
                            ) {
                                gate.authenticate(
                                    activity,
                                    titleRes = R.string.reveal_auth_title,
                                    subtitleRes = R.string.reveal_auth_subtitle,
                                )
                            } else {
                                // No usable biometric on this device: the reveal
                                // still costs something, but it can only be the
                                // PIN that already guards the vault.
                                BiometricGate.Outcome.Success
                            }
                            when (outcome) {
                                BiometricGate.Outcome.Success -> viewModel.reveal()
                                BiometricGate.Outcome.Cancelled -> Unit

                                BiometricGate.Outcome.EnrollmentChanged ->
                                    status = activity.getString(R.string.lock_biometric_changed)

                                is BiometricGate.Outcome.Error ->
                                    status = activity.getString(R.string.reveal_unavailable)
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.settings_reveal_identity))
                }
            } else {
                Text(
                    text = revealed?.handle?.let { "@$it" } ?: revealed?.displayName.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                )
                TextButton(onClick = viewModel::hideIdentity) {
                    Text(stringResource(R.string.settings_hide_identity))
                }
            }
        }

        Section(stringResource(R.string.settings_section_sync)) {
            ChoiceRow(
                label = stringResource(R.string.sync_mode_notifications),
                selected = settings.syncMode == SyncMode.ANDROID_NOTIFICATIONS,
                onClick = { viewModel.setSyncMode(SyncMode.ANDROID_NOTIFICATIONS) },
            )
            ChoiceRow(
                label = stringResource(R.string.sync_mode_api),
                selected = settings.syncMode == SyncMode.OFFICIAL_API,
                onClick = { viewModel.setSyncMode(SyncMode.OFFICIAL_API) },
            )
            if (settings.syncMode == SyncMode.ANDROID_NOTIFICATIONS &&
                !MosaicNotificationListener.isPermissionGranted(activity)
            ) {
                Text(
                    text = stringResource(R.string.warning_capture_inactive),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { MosaicIntents.openNotificationAccessSettings(activity) }) {
                    Text(stringResource(R.string.action_grant_notification_access))
                }
            }
        }

        Section(stringResource(R.string.settings_section_cooldown)) {
            Text(
                text = stringResource(R.string.cooldown_explainer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Column {
                Cooldown.presets.forEach { preset ->
                    ChoiceRow(
                        label = cooldownLabel(preset),
                        selected = settings.cooldownSeconds == preset.seconds,
                        onClick = { viewModel.setCooldownSeconds(preset.seconds) },
                    )
                }
                ChoiceRow(
                    label = stringResource(R.string.cooldown_custom),
                    selected = !Cooldown.isPreset(settings.cooldown),
                    onClick = { showCustomCooldown = true },
                )
            }
        }

        Section(stringResource(R.string.settings_section_notifications)) {
            ChoiceRow(
                label = stringResource(R.string.notifications_none),
                selected = settings.notificationPolicy == NotificationPolicy.NONE,
                onClick = { viewModel.setNotificationPolicy(NotificationPolicy.NONE) },
            )
            ChoiceRow(
                label = stringResource(R.string.notifications_neutral),
                selected = settings.notificationPolicy == NotificationPolicy.NEUTRAL,
                onClick = { viewModel.setNotificationPolicy(NotificationPolicy.NEUTRAL) },
            )
        }

        Section(stringResource(R.string.settings_section_lock)) {
            AutoLockDelay.entries.forEach { delay ->
                ChoiceRow(
                    label = autoLockLabel(delay),
                    selected = settings.autoLock == delay,
                    onClick = { viewModel.setAutoLock(delay) },
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.settings_screenshot_protection),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = settings.screenshotProtection,
                    onCheckedChange = viewModel::setScreenshotProtection,
                )
            }
            TextButton(onClick = { showPinDialog = true }) {
                Text(stringResource(R.string.settings_set_pin))
            }
        }

        Section(stringResource(R.string.settings_section_data)) {
            TextButton(onClick = { exportPicker.launch("mosaic-export.mosaicvault") }) {
                Text(stringResource(R.string.settings_export))
            }
            Text(
                text = stringResource(R.string.export_explainer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { showEraseDialog = true }) {
                Text(
                    text = stringResource(R.string.settings_erase_all),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        status?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }

        Spacer(Modifier.height(32.dp))
    }

    if (showEraseDialog) {
        EraseDialog(
            activity = activity,
            onDismiss = { showEraseDialog = false },
            onEraseConversation = {
                showEraseDialog = false
                viewModel.eraseConversation { status = activity.getString(R.string.erase_done) }
            },
            onResetEverything = {
                showEraseDialog = false
                viewModel.resetEverything(onReset)
            },
            onStatus = { status = it },
        )
    }

    if (showPinDialog) {
        PinDialog(
            onDismiss = { showPinDialog = false },
            onConfirm = { pin ->
                val ok = viewModel.setPin(pin)
                pin.fill('0')
                showPinDialog = false
                status = activity.getString(
                    if (ok) R.string.pin_saved else R.string.pin_rejected,
                )
            },
        )
    }

    if (showExportDialog) {
        PassphraseDialog(
            onDismiss = {
                showExportDialog = false
                pendingExportUri = null
            },
            onConfirm = { passphrase ->
                val uri = pendingExportUri
                showExportDialog = false
                pendingExportUri = null
                if (uri != null) {
                    viewModel.export(uri, passphrase) { ok ->
                        status = activity.getString(
                            if (ok) R.string.export_done else R.string.export_failed,
                        )
                    }
                }
            },
        )
    }

    if (showCustomCooldown) {
        CustomCooldownDialog(
            onDismiss = { showCustomCooldown = false },
            onConfirm = { seconds ->
                viewModel.setCooldownSeconds(seconds)
                showCustomCooldown = false
            },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = onClick,
            label = { Text(label) },
            leadingIcon = {
                Text(if (selected) "●" else "○")
            },
        )
    }
}

@Composable
private fun EraseDialog(
    activity: FragmentActivity,
    onDismiss: () -> Unit,
    onEraseConversation: () -> Unit,
    onResetEverything: () -> Unit,
    onStatus: (String) -> Unit,
) {
    var authenticated by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        // Confirmation is not enough for an irreversible action: prove it is the
        // owner before the destructive choices are even offered.
        val gate = MosaicGraph.biometricGate
        authenticated = if (
            gate.availability(activity) == BiometricGate.Availability.AVAILABLE
        ) {
            gate.authenticate(activity) == BiometricGate.Outcome.Success
        } else {
            // No biometric hardware: the PIN already gated entry to the vault.
            true
        }
        if (!authenticated) {
            onStatus(activity.getString(R.string.erase_auth_required))
            onDismiss()
        }
    }

    if (!authenticated) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.erase_title)) },
        text = { Text(stringResource(R.string.erase_body)) },
        confirmButton = {
            TextButton(onClick = onEraseConversation) {
                Text(stringResource(R.string.erase_conversation_only))
            }
        },
        dismissButton = {
            Column {
                TextButton(onClick = onResetEverything) {
                    Text(
                        text = stringResource(R.string.erase_reset_everything),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(onClick = { scope.launch { onDismiss() } }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
    )
}

@Composable
private fun PinDialog(onDismiss: () -> Unit, onConfirm: (CharArray) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pin_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.pin_requirements),
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 12 && it.all(Char::isDigit)) pin = it },
                    label = { Text(stringResource(R.string.pin_new)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { if (it.length <= 12 && it.all(Char::isDigit)) confirm = it },
                    label = { Text(stringResource(R.string.pin_confirm)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(pin.toCharArray()) },
                enabled = pin.length >= 6 && pin == confirm,
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun PassphraseDialog(onDismiss: () -> Unit, onConfirm: (CharArray) -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.export_passphrase_warning),
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.export_passphrase)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(passphrase.toCharArray()) },
                enabled = passphrase.length >= 10,
            ) {
                Text(stringResource(R.string.action_export))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun CustomCooldownDialog(onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    var minutes by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cooldown_custom)) },
        text = {
            OutlinedTextField(
                value = minutes,
                onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) minutes = it },
                label = { Text(stringResource(R.string.cooldown_custom_minutes)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm((minutes.toLongOrNull() ?: 0L) * 60L) },
                enabled = (minutes.toLongOrNull() ?: 0L) > 0L,
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun cooldownLabel(cooldown: Cooldown): String = when (cooldown.seconds) {
    0L -> stringResource(R.string.cooldown_off)
    30L -> stringResource(R.string.cooldown_30s)
    120L -> stringResource(R.string.cooldown_2m)
    300L -> stringResource(R.string.cooldown_5m)
    900L -> stringResource(R.string.cooldown_15m)
    else -> stringResource(R.string.cooldown_custom)
}

@Composable
private fun autoLockLabel(delay: AutoLockDelay): String = stringResource(
    when (delay) {
        AutoLockDelay.IMMEDIATE -> R.string.autolock_immediate
        AutoLockDelay.SECONDS_15 -> R.string.autolock_15s
        AutoLockDelay.MINUTE_1 -> R.string.autolock_1m
        AutoLockDelay.MINUTES_5 -> R.string.autolock_5m
        AutoLockDelay.NEVER -> R.string.autolock_never
    },
)
