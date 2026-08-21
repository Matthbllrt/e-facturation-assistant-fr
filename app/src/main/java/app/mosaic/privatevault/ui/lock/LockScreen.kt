package app.mosaic.privatevault.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import app.mosaic.privatevault.MosaicGraph
import app.mosaic.privatevault.R
import app.mosaic.privatevault.security.BiometricGate
import app.mosaic.privatevault.security.PinManager
import app.mosaic.privatevault.ui.common.MosaicMark
import kotlinx.coroutines.launch

/**
 * The locked screen.
 *
 * Shows the mark, a biometric button and — only if a PIN exists — a PIN field.
 * No app description, no alias, no hint of what is behind it. Someone who picks
 * up the phone learns nothing.
 */
@Composable
fun LockScreen(activity: FragmentActivity, onUnlocked: () -> Unit) {
    val scope = rememberCoroutineScope()
    val pinManager = MosaicGraph.pinManager
    val gate = MosaicGraph.biometricGate

    var showPin by remember { mutableStateOf(!gate.availability(activity).isUsable()) }
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val pinConfigured = remember { pinManager.isConfigured() }

    suspend fun promptBiometric() {
        busy = true
        when (gate.authenticate(activity)) {
            BiometricGate.Outcome.Success -> {
                gate.armEnrollmentMarker()
                onUnlocked()
            }

            BiometricGate.Outcome.EnrollmentChanged -> {
                // Someone added or removed a fingerprint. The biometric path is
                // refused until the PIN proves it is still the owner.
                showPin = true
                message = activity.getString(R.string.lock_biometric_changed)
            }

            BiometricGate.Outcome.Cancelled -> if (pinConfigured) showPin = true
            is BiometricGate.Outcome.Error -> {
                showPin = true
                message = activity.getString(R.string.lock_biometric_unavailable)
            }
        }
        busy = false
    }

    LaunchedEffect(Unit) {
        if (gate.availability(activity).isUsable() && !gate.enrollmentChanged()) {
            promptBiometric()
        } else {
            showPin = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            MosaicMark(modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(48.dp))

            if (!showPin) {
                Button(onClick = { scope.launch { promptBiometric() } }, enabled = !busy) {
                    Text(stringResource(R.string.lock_unlock))
                }
            } else {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { input ->
                        if (input.length <= 12 && input.all(Char::isDigit)) {
                            pin = input
                            message = null
                        }
                    },
                    label = { Text(stringResource(R.string.lock_pin_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        val entered = pin.toCharArray()
                        when (val result = pinManager.verify(entered)) {
                            PinManager.Result.Success -> {
                                gate.armEnrollmentMarker()
                                pin = ""
                                onUnlocked()
                            }

                            is PinManager.Result.Wrong -> {
                                pin = ""
                                message = activity.resources.getQuantityString(
                                    R.plurals.lock_pin_wrong,
                                    result.remainingAttempts,
                                    result.remainingAttempts,
                                )
                            }

                            is PinManager.Result.LockedOut -> {
                                pin = ""
                                message = activity.getString(
                                    R.string.lock_locked_out,
                                    (result.retryInMillis / 1000).coerceAtLeast(1),
                                )
                            }

                            PinManager.Result.NotConfigured -> {
                                message = activity.getString(R.string.lock_no_pin)
                            }
                        }
                        entered.fill('0')
                    },
                    enabled = pin.length >= 4 && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.lock_unlock))
                }

                if (gate.availability(activity).isUsable() && !gate.enrollmentChanged()) {
                    TextButton(onClick = { scope.launch { promptBiometric() } }) {
                        Text(stringResource(R.string.lock_use_biometric))
                    }
                }
            }

            message?.let {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun BiometricGate.Availability.isUsable(): Boolean =
    this == BiometricGate.Availability.AVAILABLE
