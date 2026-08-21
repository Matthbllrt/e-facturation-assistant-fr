package app.mosaic.privatevault.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.mosaic.privatevault.R
import app.mosaic.privatevault.domain.model.DeliveryStatus
import app.mosaic.privatevault.domain.model.MediaKind
import app.mosaic.privatevault.domain.model.MessageDirection
import app.mosaic.privatevault.domain.model.VaultMessage
import app.mosaic.privatevault.ui.common.AbstractAvatar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The main screen. Alias in the header, bubbles, an input row — and nothing
 * that identifies the person or the service behind it.
 */
@Composable
fun ConversationScreen(
    onOpenSettings: () -> Unit,
    onEraseRequested: () -> Unit,
    viewModel: ConversationViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .imePadding(),
    ) {
        Header(
            alias = state.alias,
            avatarSeed = state.avatarSeed,
            statusText = statusLabel(state),
            menuOpen = menuOpen,
            onMenuToggle = { menuOpen = it },
            onOpenSettings = {
                menuOpen = false
                onOpenSettings()
            },
            onErase = {
                menuOpen = false
                onEraseRequested()
            },
            onLock = {
                menuOpen = false
                viewModel.lockNow()
            },
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

        if (state.nameOnlyMatching) {
            Banner(text = stringResource(R.string.warning_name_only_matching))
        }
        if (state.vaultUnreadable) {
            Banner(text = stringResource(R.string.error_vault_unreadable))
        }
        if (!state.captureHealthy) {
            Banner(text = stringResource(R.string.warning_capture_inactive))
        }
        if (state.cleanupReady) {
            CleanupBanner(
                onOpenInstagram = viewModel::openInstagram,
                onDismiss = viewModel::dismissCleanup,
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            if (state.messages.isEmpty()) {
                EmptyState(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp,
                        vertical = 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.messages, key = { it.localId }) { message ->
                        MessageBubble(message)
                    }
                }
            }
        }

        state.notice?.let { notice ->
            Banner(text = notice, onDismiss = viewModel::dismissNotice)
        }

        Composer(
            draft = draft,
            onDraftChange = { draft = it },
            capability = state.replyCapability,
            onSend = {
                viewModel.send(draft) { viewModel.openInstagram() }
                draft = ""
            },
            onOpenInstagram = viewModel::openInstagram,
        )
    }
}

@Composable
private fun Header(
    alias: String,
    avatarSeed: Int,
    statusText: String,
    menuOpen: Boolean,
    onMenuToggle: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onErase: () -> Unit,
    onLock: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AbstractAvatar(seed = avatarSeed, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            // The alias, and only ever the alias.
            Text(text = alias, style = MaterialTheme.typography.titleMedium)
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        IconButton(onClick = onLock) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = stringResource(R.string.action_lock_now),
            )
        }

        Box {
            IconButton(onClick = { onMenuToggle(true) }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.action_menu),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuToggle(false) }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_settings)) },
                    onClick = onOpenSettings,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_erase)) },
                    onClick = onErase,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_lock_now)) },
                    onClick = onLock,
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: VaultMessage) {
    val outgoing = message.direction == MessageDirection.OUTGOING
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (outgoing) scheme.surfaceVariant else scheme.surface,
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = if (outgoing) 14.dp else 4.dp,
                bottomEnd = if (outgoing) 4.dp else 14.dp,
            ),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = message.text ?: stringResource(mediaLabel(message.mediaKind)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                    if (outgoing && message.status == DeliveryStatus.FAILED) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.status_failed),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.error,
                        )
                    }
                    if (outgoing && message.status == DeliveryStatus.PENDING) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.status_pending),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    capability: ReplyCapability,
    onSend: () -> Unit,
    onOpenInstagram: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (capability == ReplyCapability.OPEN_INSTAGRAM_ONLY) {
            // No integrated send path exists right now. Saying so plainly beats
            // a Send button that quietly does nothing.
            Text(
                text = stringResource(R.string.composer_no_direct_reply),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
            TextButton(onClick = onOpenInstagram, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_open_instagram))
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.composer_placeholder)) },
                maxLines = 5,
                shape = RoundedCornerShape(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onSend,
                enabled = draft.isNotBlank(),
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Send,
                    contentDescription = stringResource(R.string.action_send),
                )
            }
        }
    }
}

@Composable
private fun CleanupBanner(onOpenInstagram: () -> Unit, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.cleanup_ready_title),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.cleanup_ready_body),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onOpenInstagram) {
                Text(stringResource(R.string.action_open_instagram_short))
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_done))
            }
        }
    }
}

@Composable
private fun Banner(text: String, onDismiss: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (onDismiss != null) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.conversation_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.padding(horizontal = 40.dp),
    )
}

@Composable
private fun statusLabel(state: ConversationUiState): String = stringResource(
    when {
        state.vaultUnreadable -> R.string.status_vault_error
        !state.captureHealthy -> R.string.status_paused
        state.replyCapability == ReplyCapability.OFFICIAL_API -> R.string.status_synced
        else -> R.string.status_local
    },
)

private fun mediaLabel(kind: MediaKind): Int = when (kind) {
    MediaKind.IMAGE -> R.string.media_image
    MediaKind.VIDEO -> R.string.media_video
    MediaKind.AUDIO -> R.string.media_audio
    MediaKind.SHARE -> R.string.media_share
    MediaKind.STICKER -> R.string.media_sticker
    MediaKind.TEXT, MediaKind.UNAVAILABLE -> R.string.media_unavailable
}

/** Built per call: the user can change the system locale while Mosaic is alive. */
private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
