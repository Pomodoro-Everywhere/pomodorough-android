package me.egigoka.pomodorough.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.delay
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.BootstrapStrategy
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.FocusTask
import me.egigoka.pomodorough.data.HistoryItem
import me.egigoka.pomodorough.data.ResolutionRecovery
import me.egigoka.pomodorough.data.TaskDailySummary
import me.egigoka.pomodorough.data.SyncStatus
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.TimerStatus
import me.egigoka.pomodorough.data.iroh.IrohConnectionStatus
import me.egigoka.pomodorough.data.iroh.IrohIdentityRecoveryKind
import me.egigoka.pomodorough.data.iroh.IrohNetworkState
import me.egigoka.pomodorough.data.iroh.ReplicationMode

@Composable
internal fun NetworkSection(
    state: AppState,
    enabled: Boolean,
    actions: NetworkActions,
    roomNameDraft: RoomNameDraftState,
    joinCode: String,
    onJoinCodeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    joinInviteFailed: Boolean = false,
) {
    val joinInvite = JoinInviteDraft(joinCode, joinInviteFailed, onJoinCodeChange)
    NetworkSectionArrangement(state, enabled, actions, roomNameDraft, joinInvite) { parts ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            parts.routes(this)
            parts.routeDetails(this)
        }
    }
}

internal class JoinInviteDraft(val code: String, val failed: Boolean, val onCodeChange: (String) -> Unit)

// The two halves of the Network section. One column shows them in order;
// AND-29: two panes show the routes as the list and the rest as its detail.
internal class NetworkSectionParts(
    val routes: @Composable ColumnScope.() -> Unit,
    val routeDetails: @Composable ColumnScope.() -> Unit,
)

// Owns the confirmation dialogs and the draft clearing for both halves, so
// they behave the same in one column and in two panes.
@Composable
internal fun NetworkSectionArrangement(
    state: AppState,
    enabled: Boolean,
    actions: NetworkActions,
    roomNameDraft: RoomNameDraftState,
    joinInvite: JoinInviteDraft,
    arrangement: @Composable (NetworkSectionParts) -> Unit,
) {
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmRecovery by remember { mutableStateOf(false) }
    val network = state.network
    val networkActionsEnabled = enabled && network.identityRecovery == null && !network.transitioning
    ClearNetworkDraftsOnRoomOpened(network.roomId, roomNameDraft, joinInvite.onCodeChange)
    arrangement(
        NetworkSectionParts(
            routes = { NetworkRoutes(state, networkActionsEnabled, actions) { confirmRecovery = true } },
            routeDetails = {
                NetworkRouteDetails(network, networkActionsEnabled, actions, roomNameDraft, joinInvite) {
                    confirmLeave = true
                }
            },
        ),
    )
    NetworkConfirmDialogs(network, actions, confirmLeave, confirmRecovery, { confirmLeave = it }, { confirmRecovery = it })
}

@Composable
private fun NetworkRoutes(
    state: AppState,
    networkActionsEnabled: Boolean,
    actions: NetworkActions,
    onReviewRecovery: () -> Unit,
) {
    val network = state.network
    NetworkSectionHeader()
    network.identityRecovery?.let { kind ->
        IdentityRecoveryCard(kind, network.recoveryAttemptFailed, !network.transitioning, onReviewRecovery)
    }
    RouteSwitch(
        network.mode,
        network.roomId != null,
        cloudAccess(state),
        networkActionsEnabled,
        actions.onSetMode,
        actions.onSignIn,
    )
}

@Composable
private fun NetworkRouteDetails(
    network: IrohNetworkState,
    networkActionsEnabled: Boolean,
    actions: NetworkActions,
    roomNameDraft: RoomNameDraftState,
    joinInvite: JoinInviteDraft,
    onReviewLeave: () -> Unit,
) {
    NetworkStatusCard(network, networkActionsEnabled, actions.onSyncNow, onReviewLeave)
    if (network.roomId == null) {
        CreateRoomCard(roomNameDraft.name, networkActionsEnabled, roomNameDraft::onChange) {
            actions.onCreateRoom(roomNameDraft.name)
        }
        JoinRoomCard(joinInvite.code, joinInvite.failed, networkActionsEnabled, joinInvite.onCodeChange) {
            actions.onJoinRoom(joinInvite.code)
        }
    }
    network.invite?.let { RoomInviteCard(it, networkActionsEnabled, actions) }
    NetworkPrivacyCard()
    CrashReportingCard()
}

@Composable
private fun NetworkConfirmDialogs(
    network: IrohNetworkState,
    actions: NetworkActions,
    confirmLeave: Boolean,
    confirmRecovery: Boolean,
    onConfirmLeaveChange: (Boolean) -> Unit,
    onConfirmRecoveryChange: (Boolean) -> Unit,
) {
    if (confirmLeave) {
        LeaveRoomDialog(
            onConfirm = { onConfirmLeaveChange(false); actions.onLeaveRoom() },
            onDismiss = { onConfirmLeaveChange(false) },
        )
    }
    if (confirmRecovery) {
        network.identityRecovery?.let { kind ->
            IdentityRecoveryDialog(
                kind = kind,
                onConfirm = { onConfirmRecoveryChange(false); actions.onConfirmIdentityRecovery() },
                onDismiss = { onConfirmRecoveryChange(false) },
            )
        }
    }
}

@Composable
private fun ClearNetworkDraftsOnRoomOpened(
    roomId: String?,
    roomNameDraft: RoomNameDraftState,
    onJoinCodeChange: (String) -> Unit,
) {
    androidx.compose.runtime.LaunchedEffect(roomId) {
        if (roomId != null) {
            roomNameDraft.clear()
            onJoinCodeChange("")
        }
    }
}

internal data class NetworkActions(
    val onSetMode: (ReplicationMode) -> Unit,
    val onCreateRoom: (String) -> Unit,
    val onJoinRoom: (String) -> Unit,
    val onLeaveRoom: () -> Unit,
    val onRefreshInvite: () -> Unit,
    val onSyncNow: () -> Unit,
    val onConfirmIdentityRecovery: () -> Unit = {},
    val onShareInvite: (String) -> Unit,
    val onSignIn: () -> Unit = {},
)

@Composable
private fun IdentityRecoveryCard(
    kind: IrohIdentityRecoveryKind,
    previousAttemptFailed: Boolean,
    enabled: Boolean,
    onReview: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(identityRecoveryTitle(kind), style = MaterialTheme.typography.titleLarge)
            Text(identityRecoveryDescription(kind))
            if (previousAttemptFailed) Text(stringResource(R.string.iroh_identity_recovery_failed))
            Button(
                onClick = onReview,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
            ) {
                Text(identityRecoveryReviewLabel(kind))
            }
        }
    }
}

@Composable
private fun IdentityRecoveryDialog(
    kind: IrohIdentityRecoveryKind,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(identityRecoveryDialogTitle(kind)) },
        text = { Text(identityRecoveryDialogDescription(kind)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(identityRecoveryConfirmLabel(kind), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun identityRecoveryTitle(kind: IrohIdentityRecoveryKind): String = stringResource(
    when (kind) {
        IrohIdentityRecoveryKind.ENDPOINT_CORRUPTED -> R.string.iroh_endpoint_identity_needs_repair
        IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING -> R.string.iroh_identity_key_is_unavailable
    },
)

@Composable
private fun identityRecoveryDescription(kind: IrohIdentityRecoveryKind): String = stringResource(
    when (kind) {
        IrohIdentityRecoveryKind.ENDPOINT_CORRUPTED -> R.string.iroh_endpoint_identity_repair_description
        IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING -> R.string.iroh_identity_reset_description
    },
)

@Composable
private fun identityRecoveryReviewLabel(kind: IrohIdentityRecoveryKind): String = stringResource(
    when (kind) {
        IrohIdentityRecoveryKind.ENDPOINT_CORRUPTED -> R.string.review_iroh_endpoint_repair
        IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING -> R.string.review_iroh_identity_reset
    },
)

@Composable
private fun identityRecoveryDialogTitle(kind: IrohIdentityRecoveryKind): String = stringResource(
    when (kind) {
        IrohIdentityRecoveryKind.ENDPOINT_CORRUPTED -> R.string.repair_iroh_endpoint_identity_question
        IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING -> R.string.reset_iroh_identity_and_rooms_question
    },
)

@Composable
private fun identityRecoveryDialogDescription(kind: IrohIdentityRecoveryKind): String = stringResource(
    when (kind) {
        IrohIdentityRecoveryKind.ENDPOINT_CORRUPTED -> R.string.repair_iroh_endpoint_identity_description
        IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING -> R.string.reset_iroh_identity_and_rooms_description
    },
)

@Composable
private fun identityRecoveryConfirmLabel(kind: IrohIdentityRecoveryKind): String = stringResource(
    when (kind) {
        IrohIdentityRecoveryKind.ENDPOINT_CORRUPTED -> R.string.repair_endpoint
        IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING -> R.string.reset_iroh_identity
    },
)

@Composable
private fun NetworkSectionHeader() {
    SectionLabel(stringResource(R.string.network_route))
    Text(stringResource(R.string.choose_where_time_travels), style = MaterialTheme.typography.headlineMedium)
    Text(
        stringResource(R.string.your_clock_always_works_on_this_device_routes),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun NetworkStatusCard(
    network: IrohNetworkState,
    enabled: Boolean,
    onSyncNow: () -> Unit,
    onLeave: () -> Unit,
) {
    Surface(color = Ink, contentColor = darkModeTextColor(Cloud), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(networkStatusTitle(network.status), style = MaterialTheme.typography.titleLarge)
            Text(
                network.message ?: networkStatusDescription(network.status),
                color = darkModeTextColor(Lavender),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            network.roomId?.let { NetworkRoomDetails(network, it) }
            if (network.mode == ReplicationMode.IROH && network.roomId != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = onSyncNow,
                        enabled = enabled,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.sync_now))
                    }
                    TextButton(
                        onClick = onLeave,
                        enabled = enabled,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.leave_room), color = dangerTextColorOnInk())
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkRoomDetails(network: IrohNetworkState, roomId: String) {
    Text(
        network.roomName ?: stringResource(R.string.unnamed_room),
        color = darkModeTextColor(Butter),
        style = MaterialTheme.typography.labelLarge,
    )
    Text(
        stringResource(R.string.room_identifier, roomId.take(8), roomId.takeLast(6)),
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.labelMedium,
    )
    Text(
        stringResource(
            R.string.network_counts,
            pluralStringResource(R.plurals.saved_peers, network.peerCount, network.peerCount),
            pluralStringResource(R.plurals.durable_records, network.operationCount, network.operationCount),
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun CreateRoomCard(
    roomName: String,
    enabled: Boolean,
    onNameChange: (String) -> Unit,
    onCreate: () -> Unit,
) {
    val roomNameLabel = stringResource(R.string.room_name_optional)
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = darkModeTextColor(Ink),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.create_a_room), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = roomName,
                onValueChange = onNameChange,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = roomNameLabel },
                label = { Text(roomNameLabel, Modifier.clearAndSetSemantics {}) },
                supportingText = { Text(stringResource(R.string.copy_1_64_characters_when_set)) },
                singleLine = true,
            )
            Button(
                onClick = onCreate,
                enabled = enabled && roomName.codePointCount(0, roomName.length) <= 64,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = createRoomButtonColors(),
            ) { Text(stringResource(R.string.create_iroh_room)) }
        }
    }
}

// AND-19: the card is Violet in the dark theme, so a Violet button vanished on it.
// primary is Violet with Cloud text in light and the light blue with Ink text in dark.
@Composable
internal fun createRoomButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.primary,
    contentColor = MaterialTheme.colorScheme.onPrimary,
)

@Composable
private fun JoinRoomCard(
    joinCode: String,
    joinFailed: Boolean,
    enabled: Boolean,
    onCodeChange: (String) -> Unit,
    onJoin: () -> Unit,
) {
    val inviteLabel = stringResource(R.string.room_invite)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.join_a_room), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = joinCode,
                onValueChange = onCodeChange,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = inviteLabel },
                label = { Text(inviteLabel, Modifier.clearAndSetSemantics {}) },
                placeholder = { Text(stringResource(R.string.pomodorough1)) },
                // AND-16: a failed join names itself under the field, where the user typed.
                isError = joinFailed,
                supportingText = if (joinFailed) {
                    { JoinInviteFailure() }
                } else null,
                minLines = 3,
                maxLines = 6,
            )
            Button(
                onClick = onJoin,
                enabled = enabled && joinCode.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = darkModeTextColor(Cloud)),
            ) { Text(stringResource(R.string.join_room)) }
        }
    }
}

@Composable
private fun JoinInviteFailure() {
    Text(
        stringResource(R.string.room_invite_join_failed),
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun RoomInviteCard(invite: String, enabled: Boolean, actions: NetworkActions) {
    val description = stringResource(R.string.iroh_room_invite_treat_as_full_read_and)
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = darkModeTextColor(Ink),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.room_pass), style = MaterialTheme.typography.titleLarge)
            Text(
                invite,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { contentDescription = description },
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { actions.onShareInvite(invite) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.share))
                }
                TextButton(
                    onClick = actions.onRefreshInvite,
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.refresh))
                }
            }
        }
    }
}

@Composable
private fun NetworkPrivacyCard() {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(R.string.privacy_access))
            Text(stringResource(R.string.room_invites_grant_full_read_and_write_access))
            Text(
                stringResource(R.string.direct_connections_reveal_peer_ip_addresses_relay_traffic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.peer_networking_runs_only_while_pomodorough_is_in),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LeaveRoomDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.leave_iroh_room)) },
        text = { Text(stringResource(R.string.your_previous_on_device_or_cloud_workspace_will)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(
                    stringResource(R.string.leave_and_restore),
                    color = dangerTextColor(),
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.stay_in_room))
            }
        },
    )
}

@Composable
internal fun networkStatusTitle(status: IrohConnectionStatus): String = when (status) {
    IrohConnectionStatus.STOPPED -> stringResource(R.string.route_stopped)
    IrohConnectionStatus.STARTING -> stringResource(R.string.opening_route)
    IrohConnectionStatus.LISTENING -> stringResource(R.string.ready_for_peers)
    IrohConnectionStatus.SYNCING -> stringResource(R.string.exchanging_changes)
    IrohConnectionStatus.WAITING_FOR_PEERS -> stringResource(R.string.waiting_for_peers)
    IrohConnectionStatus.CONFLICT -> stringResource(R.string.repair_required)
    IrohConnectionStatus.UNAVAILABLE -> stringResource(R.string.route_unavailable)
}

@Composable
internal fun networkStatusDescription(status: IrohConnectionStatus): String = when (status) {
    IrohConnectionStatus.STOPPED -> stringResource(R.string.no_peer_endpoint_is_running)
    IrohConnectionStatus.STARTING -> stringResource(R.string.binding_encrypted_iroh_transport)
    IrohConnectionStatus.LISTENING -> stringResource(R.string.foreground_endpoint_is_listening_on_pomodorough_sync_v1)
    IrohConnectionStatus.SYNCING -> stringResource(R.string.pulling_bounded_inventory_and_immutable_operations)
    IrohConnectionStatus.WAITING_FOR_PEERS -> stringResource(R.string.no_peer_is_online_local_changes_remain_durable)
    IrohConnectionStatus.CONFLICT -> stringResource(R.string.two_payloads_claim_one_immutable_operation_id_replication)
    IrohConnectionStatus.UNAVAILABLE -> stringResource(R.string.use_on_device_or_cloud_mode_while_this)
}
