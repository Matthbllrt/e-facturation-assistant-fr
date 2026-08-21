package app.mosaic.privatevault.sync.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput
import app.mosaic.privatevault.core.log.SafeLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Replies to the target by re-using Instagram's own Android reply action.
 *
 * This is the only sanctioned way to answer from outside Instagram without
 * credentials: the notification carries a [PendingIntent] plus a [RemoteInput],
 * and firing it is precisely what the system's own inline reply does.
 *
 * The handle cannot be persisted — a PendingIntent dies with the notification
 * and with the process — so availability is honest and transient: it appears
 * when a repliable notification arrives and disappears when Instagram
 * withdraws it. The UI degrades to "Ouvrir Instagram" rather than pretending.
 */
object ReplyDispatcher {

    data class Handle(
        val pendingIntent: PendingIntent,
        val remoteInputs: Array<android.app.RemoteInput>,
        val resultKey: String,
        val capturedAt: Long,
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private val handleState = MutableStateFlow<Handle?>(null)

    /** Observed by the conversation screen to decide which send button to show. */
    val available: StateFlow<Handle?> = handleState.asStateFlow()

    fun remember(action: Notification.Action) {
        val inputs = action.remoteInputs?.filter { it.allowFreeFormInput }?.toTypedArray()
        val key = inputs?.firstOrNull()?.resultKey
        if (inputs.isNullOrEmpty() || key.isNullOrEmpty()) {
            SafeLog.d("reply.action_without_free_form_input")
            return
        }
        handleState.value = Handle(
            pendingIntent = action.actionIntent,
            remoteInputs = inputs,
            resultKey = key,
            capturedAt = System.currentTimeMillis(),
        )
        SafeLog.d("reply.action_captured")
    }

    fun forget() {
        handleState.value = null
    }

    sealed interface Result {
        data object Sent : Result

        /** No live reply action: the caller falls back to opening Instagram. */
        data object Unavailable : Result
        data class Failed(val reason: String) : Result
    }

    fun send(context: Context, text: String): Result {
        val handle = handleState.value ?: return Result.Unavailable
        if (text.isBlank()) return Result.Failed("empty")

        return try {
            val bundle = Bundle().apply { putCharSequence(handle.resultKey, text) }
            val intent = Intent()
            RemoteInput.addResultsToIntent(
                handle.remoteInputs.map { it.toCompat() }.toTypedArray(),
                intent,
                bundle,
            )
            handle.pendingIntent.send(context, 0, intent)
            SafeLog.d("reply.sent_via_remote_input")
            Result.Sent
        } catch (error: PendingIntent.CanceledException) {
            // Instagram dismissed the notification between render and tap.
            handleState.value = null
            SafeLog.w("reply.pending_intent_cancelled", error)
            Result.Unavailable
        } catch (error: Exception) {
            SafeLog.e("reply.send_failed", error)
            Result.Failed(error::class.java.simpleName)
        }
    }

    private fun android.app.RemoteInput.toCompat(): RemoteInput =
        RemoteInput.Builder(resultKey)
            .setLabel(label)
            .setAllowFreeFormInput(allowFreeFormInput)
            .build()
}
