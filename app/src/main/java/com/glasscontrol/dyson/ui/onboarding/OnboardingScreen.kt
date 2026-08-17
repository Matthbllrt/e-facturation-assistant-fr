package com.glasscontrol.dyson.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glasscontrol.dyson.domain.model.DysonState
import com.glasscontrol.dyson.ui.components.AnimatedDyson
import com.glasscontrol.dyson.ui.components.GlassCard
import com.glasscontrol.dyson.ui.theme.LocalGlassColors

/**
 * First-run setup.
 *
 * Two routes are offered on purpose: the MyDyson account is the easy path, and
 * manual entry keeps the app working if Dyson ever changes its cloud API — the
 * local MQTT protocol is what actually matters, and it needs no account.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val glass = LocalGlassColors.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
    ) {
        AnimatedContent(
            targetState = state.step,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "onboarding",
        ) { step ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                when (step) {
                    OnboardingStep.WELCOME -> Welcome(viewModel)
                    OnboardingStep.METHOD -> MethodChoice(viewModel)
                    OnboardingStep.CLOUD_EMAIL -> CloudEmail(state, viewModel)
                    OnboardingStep.CLOUD_CODE -> CloudCode(state, viewModel)
                    OnboardingStep.CLOUD_DEVICES -> CloudDevices(state, viewModel, onFinished)
                    OnboardingStep.MANUAL -> ManualSetup(state, viewModel, onFinished)
                    OnboardingStep.DONE -> Unit
                }

                state.error?.let { message ->
                    Text(
                        text = message,
                        color = glass.danger,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 18.dp),
                    )
                }

                if (state.busy) {
                    CircularProgressIndicator(
                        color = glass.accent,
                        modifier = Modifier
                            .padding(top = 22.dp)
                            .size(26.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Welcome(viewModel: OnboardingViewModel) {
    val glass = LocalGlassColors.current

    AnimatedDyson(
        state = DysonState(power = true, fanSpeed = 4, oscillation = true),
        modifier = Modifier
            .width(200.dp)
            .height(300.dp),
    )
    Text(
        text = "D Y S O N",
        style = MaterialTheme.typography.titleMedium,
        color = glass.onSurface,
        modifier = Modifier.padding(top = 14.dp),
    )
    Text(
        text = "Votre Dyson.\nDirectement sur votre écran d'accueil.",
        style = MaterialTheme.typography.headlineMedium,
        color = glass.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 12.dp),
    )
    PrimaryButton(
        text = "CONNECTER MON DYSON",
        modifier = Modifier.padding(top = 34.dp),
        onClick = { viewModel.goTo(OnboardingStep.METHOD) },
    )
}

@Composable
private fun MethodChoice(viewModel: OnboardingViewModel) {
    val glass = LocalGlassColors.current

    SectionTitle("Comment souhaitez-vous connecter ?")

    GlassCard(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Column {
            Text("Compte MyDyson", style = MaterialTheme.typography.titleMedium, color = glass.onSurface)
            Text(
                text = "Récupère automatiquement vos appareils et leurs identifiants locaux.",
                style = MaterialTheme.typography.bodyMedium,
                color = glass.onSurfaceMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
            PrimaryButton(
                text = "Utiliser mon compte",
                modifier = Modifier.padding(top = 16.dp),
                onClick = { viewModel.goTo(OnboardingStep.CLOUD_EMAIL) },
            )
        }
    }

    GlassCard(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Column {
            Text("Configuration manuelle", style = MaterialTheme.typography.titleMedium, color = glass.onSurface)
            Text(
                text = "Sans compte, à partir des informations de l'appareil. Fonctionne même si l'API Dyson change.",
                style = MaterialTheme.typography.bodyMedium,
                color = glass.onSurfaceMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
            TextButton(onClick = { viewModel.goTo(OnboardingStep.MANUAL) }) {
                Text("Configurer manuellement", color = glass.accent)
            }
        }
    }
}

@Composable
private fun CloudEmail(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    SectionTitle("Votre compte MyDyson")
    Subtitle("Un code de vérification vous sera envoyé par e-mail.")

    GlassField(
        value = state.email,
        onValueChange = viewModel::onEmailChange,
        label = "Adresse e-mail",
        keyboardType = KeyboardType.Email,
        modifier = Modifier.padding(top = 20.dp),
    )
    GlassField(
        value = state.region,
        onValueChange = viewModel::onRegionChange,
        label = "Pays (code ISO, ex. FR)",
        modifier = Modifier.padding(top = 12.dp),
    )
    PrimaryButton(
        text = "RECEVOIR LE CODE",
        enabled = !state.busy,
        modifier = Modifier.padding(top = 22.dp),
        onClick = viewModel::requestCode,
    )
    BackLink { viewModel.goTo(OnboardingStep.METHOD) }
}

@Composable
private fun CloudCode(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    SectionTitle("Vérification")
    Subtitle("Saisissez le code reçu par e-mail ainsi que le mot de passe de votre compte MyDyson. Le mot de passe n'est jamais conservé.")

    GlassField(
        value = state.otpCode,
        onValueChange = viewModel::onOtpChange,
        label = "Code de vérification",
        keyboardType = KeyboardType.Number,
        modifier = Modifier.padding(top = 20.dp),
    )
    GlassField(
        value = state.password,
        onValueChange = viewModel::onPasswordChange,
        label = "Mot de passe MyDyson",
        isPassword = true,
        modifier = Modifier.padding(top = 12.dp),
    )
    PrimaryButton(
        text = "VALIDER",
        enabled = !state.busy,
        modifier = Modifier.padding(top = 22.dp),
        onClick = viewModel::verifyCode,
    )
    BackLink { viewModel.goTo(OnboardingStep.CLOUD_EMAIL) }
}

@Composable
private fun CloudDevices(
    state: OnboardingUiState,
    viewModel: OnboardingViewModel,
    onFinished: () -> Unit,
) {
    val glass = LocalGlassColors.current
    SectionTitle("Choisissez votre appareil")

    state.devices.forEach { device ->
        GlassCard(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Column {
                Text(device.name, style = MaterialTheme.typography.titleMedium, color = glass.onSurface)
                Text(
                    text = device.displayModel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = glass.onSurfaceMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
                PrimaryButton(
                    text = "SÉLECTIONNER",
                    enabled = !state.busy,
                    modifier = Modifier.padding(top = 14.dp),
                    onClick = { viewModel.selectCloudDevice(device, onFinished) },
                )
            }
        }
    }

    if (state.devices.isEmpty()) {
        TextButton(onClick = { viewModel.goTo(OnboardingStep.MANUAL) }) {
            Text("Configurer manuellement", color = glass.accent)
        }
    }
}

@Composable
private fun ManualSetup(
    state: OnboardingUiState,
    viewModel: OnboardingViewModel,
    onFinished: () -> Unit,
) {
    val glass = LocalGlassColors.current

    SectionTitle("Configuration manuelle")
    Subtitle("Ces informations figurent sur l'étiquette sous l'appareil ou derrière le filtre.")

    TextButton(onClick = viewModel::scanNetwork, modifier = Modifier.padding(top = 8.dp)) {
        Text("Rechercher sur le Wi-Fi", color = glass.accent)
    }

    state.discovered.forEach { device ->
        GlassCard(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), contentPadding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(device.serial, style = MaterialTheme.typography.bodyMedium, color = glass.onSurface)
                    Text(
                        text = "${device.host} · type ${device.deviceType}",
                        style = MaterialTheme.typography.labelSmall,
                        color = glass.onSurfaceMuted,
                    )
                }
                TextButton(onClick = { viewModel.useDiscovered(device) }) {
                    Text("Utiliser", color = glass.accent)
                }
            }
        }
    }

    GlassField(
        value = state.manualHost,
        onValueChange = viewModel::onManualHostChange,
        label = "Adresse IP ou nom d'hôte",
        modifier = Modifier.padding(top = 8.dp),
    )
    GlassField(
        value = state.manualSerial,
        onValueChange = viewModel::onManualSerialChange,
        label = "Numéro de série (ex. NN2-EU-ABC1234A)",
        modifier = Modifier.padding(top = 12.dp),
    )
    GlassField(
        value = state.manualDeviceType,
        onValueChange = viewModel::onManualTypeChange,
        label = "Type d'appareil (ex. 438, 527K)",
        modifier = Modifier.padding(top = 12.dp),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Dériver du mot de passe Wi-Fi",
                style = MaterialTheme.typography.bodyMedium,
                color = glass.onSurface,
            )
            Text(
                text = "Recommandé : celui imprimé sur l'étiquette de l'appareil.",
                style = MaterialTheme.typography.labelSmall,
                color = glass.onSurfaceMuted,
            )
        }
        Switch(checked = state.useWifiPassword, onCheckedChange = viewModel::onUseWifiPasswordChange)
    }

    if (state.useWifiPassword) {
        GlassField(
            value = state.manualWifiPassword,
            onValueChange = viewModel::onWifiPasswordChange,
            label = "Mot de passe Wi-Fi de l'appareil",
            isPassword = true,
            modifier = Modifier.padding(top = 12.dp),
        )
    } else {
        GlassField(
            value = state.manualCredential,
            onValueChange = viewModel::onManualCredentialChange,
            label = "Identifiant MQTT local",
            isPassword = true,
            modifier = Modifier.padding(top = 12.dp),
        )
    }

    PrimaryButton(
        text = "CONNECTER",
        enabled = !state.busy,
        modifier = Modifier.padding(top = 22.dp),
        onClick = { viewModel.saveManual(onFinished) },
    )
    BackLink { viewModel.goTo(OnboardingStep.METHOD) }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        color = LocalGlassColors.current.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Subtitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = LocalGlassColors.current.onSurfaceMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
    )
}

@Composable
private fun BackLink(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.padding(top = 6.dp)) {
        Text("Retour", color = LocalGlassColors.current.onSurfaceMuted)
    }
}

@Composable
private fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val glass = LocalGlassColors.current
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = glass.accent.copy(alpha = if (glass.dark) 0.22f else 0.14f),
            contentColor = glass.accent,
            disabledContainerColor = glass.surface,
            disabledContentColor = glass.onSurfaceMuted,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun GlassField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val glass = LocalGlassColors.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = glass.onSurfaceMuted) },
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = glass.onSurface,
            unfocusedTextColor = glass.onSurface,
            focusedBorderColor = glass.accent,
            unfocusedBorderColor = glass.border,
            cursorColor = glass.accent,
        ),
    )
}
