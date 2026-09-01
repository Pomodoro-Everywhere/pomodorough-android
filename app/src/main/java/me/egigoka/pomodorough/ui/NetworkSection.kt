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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import me.egigoka.pomodorough.data.AuthStatus
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
    modifier: Modifier = Modifier,
) {
    var roomName by remember { mutableStateOf("") }
    var joinCode by remember { mutableStateOf("") }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmRecovery by remember { mutableStateOf(false) }
    val network = state.network
    val networkActionsEnabled = enabled && network.identityRecovery == null && !network.transitioning
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        NetworkSectionHeader()
        network.identityRecovery?.let { kind ->
            IdentityRecoveryCard(
                kind,
                network.recoveryAttemptFailed,
                !network.transitioning,
            ) { confirmRecovery = true }
        }
        RouteSwitch(network.mode, network.roomId != null, networkActionsEnabled, actions.onSetMode)
        NetworkStatusCard(network, networkActionsEnabled, actions.onSyncNow) { confirmLeave = true }
        if (network.roomId == null) {
            CreateRoomCard(roomName, networkActionsEnabled, { roomName = it }) { actions.onCreateRoom(roomName) }
            JoinRoomCard(joinCode, networkActionsEnabled, { joinCode = it }) { actions.onJoinRoom(joinCode) }
        }
        network.invite?.let { RoomInviteCard(it, networkActionsEnabled, actions) }
        NetworkPrivacyCard()
    }
    if (confirmLeave) {
        LeaveRoomDialog(
            onConfirm = { confirmLeave = false; actions.onLeaveRoom() },
            onDismiss = { confirmLeave = false },
        )
    }
    if (confirmRecovery) {
        network.identityRecovery?.let { kind ->
            IdentityRecoveryDialog(
                kind = kind,
                onConfirm = { confirmRecovery = false; actions.onConfirmIdentityRecovery() },
                onDismiss = { confirmRecovery = false },
            )
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
                        Text(stringResource(R.string.leave_room), color = darkModeTextColor(Danger))
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
            Text(stringResource(R.string.open_a_peer_route), style = MaterialTheme.typography.titleLarge)
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
                modifier = Modifier.fillMaxWidth().height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Violet),
            ) { Text(stringResource(R.string.create_iroh_room)) }
        }
    }
}

@Composable
private fun JoinRoomCard(
    joinCode: String,
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
            Text(stringResource(R.string.join_an_existing_route), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = joinCode,
                onValueChange = onCodeChange,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = inviteLabel },
                label = { Text(inviteLabel, Modifier.clearAndSetSemantics {}) },
                placeholder = { Text(stringResource(R.string.pomodorough1)) },
                minLines = 3,
                maxLines = 6,
            )
            Button(
                onClick = onJoin,
                enabled = enabled && joinCode.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink),
            ) { Text(stringResource(R.string.join_room)) }
        }
    }
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
                    color = darkModeTextColor(MaterialTheme.colorScheme.error),
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
internal fun RouteSwitch(
    active: ReplicationMode,
    hasIrohRoom: Boolean,
    enabled: Boolean,
    onSetMode: (ReplicationMode) -> Unit,
) {
    val choices = listOf(
        ReplicationMode.OFFLINE to (stringResource(R.string.on_device) to stringResource(R.string.no_remote_endpoint)),
        ReplicationMode.IROH to (stringResource(R.string.iroh_room) to stringResource(R.string.equal_peers)),
        ReplicationMode.CENTRALIZED to (stringResource(R.string.cloud) to stringResource(R.string.signed_in_server)),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { (mode, copy) ->
            RouteChoice(mode, copy, active == mode, hasIrohRoom, enabled) { onSetMode(mode) }
        }
    }
}

@Composable
private fun RouteChoice(
    mode: ReplicationMode,
    copy: Pair<String, String>,
    selected: Boolean,
    hasIrohRoom: Boolean,
    mutationsEnabled: Boolean,
    onSelect: () -> Unit,
) {
    val enabled = mutationsEnabled && (mode != ReplicationMode.IROH || hasIrohRoom)
    val selectionDescription = stringResource(if (selected) R.string.selected else R.string.not_selected)
    Surface(
        onClick = onSelect,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 62.dp).semantics {
            stateDescription = selectionDescription
        },
        color = if (selected) Violet else MaterialTheme.colorScheme.surface,
        contentColor = darkModeTextColor(if (selected) Cloud else MaterialTheme.colorScheme.onSurface),
        shape = RoundedCornerShape(
            topStart = if (mode == ReplicationMode.OFFLINE) 28.dp else 14.dp,
            topEnd = 14.dp,
            bottomStart = 14.dp,
            bottomEnd = if (mode == ReplicationMode.CENTRALIZED) 28.dp else 14.dp,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(14.dp).background(
                    if (selected) MaterialTheme.colorScheme.tertiaryContainer else Lavender,
                    CircleShape,
                ),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(copy.first, style = MaterialTheme.typography.labelLarge)
                Text(
                    if (mode == ReplicationMode.IROH && !hasIrohRoom) {
                        stringResource(R.string.create_or_join_a_room_below_first)
                    } else copy.second,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                stringResource(if (selected) R.string.active else R.string.select),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
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
