package app.mosaic.privatevault.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.mosaic.privatevault.R
import app.mosaic.privatevault.core.crypto.BiometricEnrollmentGuard
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * BiometricPrompt wrapped as a suspending call, plus the enrolment check.
 *
 * Two distinct gates use it: opening the vault, and revealing the real
 * Instagram identity. The second one always prompts again, even if the vault is
 * already open — that is the point of it.
 */
class BiometricGate(
    private val enrollmentGuard: BiometricEnrollmentGuard = BiometricEnrollmentGuard(),
) {

    enum class Availability { AVAILABLE, NONE_ENROLLED, UNAVAILABLE }

    sealed interface Outcome {
        data object Success : Outcome
        data object Cancelled : Outcome

        /** Biometrics changed since setup: the PIN is required instead. */
        data object EnrollmentChanged : Outcome
        data class Error(val code: Int) : Outcome
    }

    fun availability(activity: FragmentActivity): Availability {
        val manager = BiometricManager.from(activity)
        return when (manager.canAuthenticate(AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Availability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Availability.NONE_ENROLLED
            else -> Availability.UNAVAILABLE
        }
    }

    fun enrollmentChanged(): Boolean =
        enrollmentGuard.state() == BiometricEnrollmentGuard.State.INVALIDATED

    fun armEnrollmentMarker() {
        enrollmentGuard.arm()
    }

    fun clearEnrollmentMarker() {
        enrollmentGuard.clear()
    }

    suspend fun authenticate(
        activity: FragmentActivity,
        titleRes: Int = R.string.auth_title,
        subtitleRes: Int = R.string.auth_subtitle,
    ): Outcome {
        if (enrollmentChanged()) return Outcome.EnrollmentChanged

        return suspendCancellableCoroutine { continuation ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) continuation.resume(Outcome.Success)
                    }

                    override fun onAuthenticationError(code: Int, message: CharSequence) {
                        if (!continuation.isActive) return
                        val outcome = when (code) {
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            BiometricPrompt.ERROR_CANCELED,
                            -> Outcome.Cancelled

                            else -> Outcome.Error(code)
                        }
                        continuation.resume(outcome)
                    }

                    // onAuthenticationFailed is a rejected finger, not a result:
                    // the prompt stays up and the user tries again.
                },
            )

            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(titleRes))
                .setSubtitle(activity.getString(subtitleRes))
                .setNegativeButtonText(activity.getString(R.string.auth_use_pin))
                .setAllowedAuthenticators(AUTHENTICATORS)
                .setConfirmationRequired(false)
                .build()

            prompt.authenticate(info)
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }

    private companion object {
        /**
         * Strong biometrics only. Device credential is deliberately excluded:
         * anyone who can unlock the phone should not automatically be able to
         * open the vault — that is what the separate PIN is for.
         */
        const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG
    }
}
