package me.egigoka.pomodorough.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.AuthStatus
import me.egigoka.pomodorough.data.iroh.ReplicationMode

/** What the Cloud route row can offer for the current account state. */
internal enum class CloudAccess {
    SignedIn,

    /** Signed out with nothing else to finish first: the row starts sign-in. */
    SignInOffered,

    /** The account is loading, signing in, or needs a local reset first. */
    Pending,
}

internal fun cloudAccess(state: AppState): CloudAccess = when {
    state.authStatus == AuthStatus.SignedIn -> CloudAccess.SignedIn
    state.authStatus == AuthStatus.SignedOut && !state.localAccountResetRequired -> CloudAccess.SignInOffered
    else -> CloudAccess.Pending
}

/** What tapping a route row does. */
private enum class RouteRowAction { Select, SignIn, Unavailable }

private data class RouteRow(
    val mode: ReplicationMode,
    val title: String,
    val detail: String,
    val action: RouteRowAction,
)

@Composable
internal fun RouteSwitch(
    active: ReplicationMode,
    hasIrohRoom: Boolean,
    cloudAccess: CloudAccess,
    enabled: Boolean,
    onSetMode: (ReplicationMode) -> Unit,
    onSignIn: () -> Unit,
) {
    // AND-08: Cloud stays on this device until sign-in, so the route in use is On device, as the
    // header chip says. Cloud is never marked ACTIVE without an account.
    val routeInUse = if (active == ReplicationMode.CENTRALIZED && cloudAccess != CloudAccess.SignedIn) {
        ReplicationMode.OFFLINE
    } else active
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        routeRows(hasIrohRoom, cloudAccess, enabled).forEach { row ->
            RouteChoice(row, routeInUse == row.mode) {
                if (row.action == RouteRowAction.SignIn) onSignIn() else onSetMode(row.mode)
            }
        }
    }
}

private fun routeAction(enabled: Boolean, available: Boolean): RouteRowAction =
    if (enabled && available) RouteRowAction.Select else RouteRowAction.Unavailable

private fun cloudRowAction(cloudAccess: CloudAccess, enabled: Boolean): RouteRowAction = when {
    !enabled -> RouteRowAction.Unavailable
    cloudAccess == CloudAccess.SignedIn -> RouteRowAction.Select
    cloudAccess == CloudAccess.SignInOffered -> RouteRowAction.SignIn
    else -> RouteRowAction.Unavailable
}

@Composable
private fun routeRows(hasIrohRoom: Boolean, cloudAccess: CloudAccess, enabled: Boolean): List<RouteRow> {
    val irohDetail = if (hasIrohRoom) R.string.equal_peers else R.string.create_or_join_a_room_below_first
    // Cloud mode stays local until sign-in; the header already says "On device", so the card must not claim a server.
    val cloudDetail = if (cloudAccess == CloudAccess.SignedIn) {
        R.string.signed_in_server
    } else R.string.sign_in_to_sync_across_devices
    return listOf(
        RouteRow(
            ReplicationMode.OFFLINE,
            stringResource(R.string.on_device),
            stringResource(R.string.no_remote_endpoint),
            routeAction(enabled, available = true),
        ),
        RouteRow(
            ReplicationMode.IROH,
            stringResource(R.string.iroh_room),
            stringResource(irohDetail),
            routeAction(enabled, available = hasIrohRoom),
        ),
        RouteRow(
            ReplicationMode.CENTRALIZED,
            stringResource(R.string.cloud),
            stringResource(cloudDetail),
            cloudRowAction(cloudAccess, enabled),
        ),
    )
}

// AND-11: an unavailable row that is not in use draws its content and border at the disabled
// emphasis, so it no longer reads as a row the user can pick, and it offers no trailing action.
@Composable
private fun RouteChoice(row: RouteRow, selected: Boolean, onClick: () -> Unit) {
    val dimmed = row.action == RouteRowAction.Unavailable && !selected
    val selectionDescription = stringResource(if (selected) R.string.selected else R.string.not_selected)
    val baseContent = darkModeTextColor(if (selected) Cloud else MaterialTheme.colorScheme.onSurface)
    Surface(
        onClick = onClick,
        enabled = row.action != RouteRowAction.Unavailable,
        modifier = Modifier.fillMaxWidth().heightIn(min = 62.dp).semantics {
            this.selected = selected
            stateDescription = selectionDescription
            role = if (row.action == RouteRowAction.SignIn) Role.Button else Role.RadioButton
        },
        color = if (selected) Violet else MaterialTheme.colorScheme.surface,
        contentColor = if (dimmed) baseContent.copy(alpha = DisabledContentAlpha) else baseContent,
        shape = routeShape(row.mode),
        border = BorderStroke(
            1.dp,
            if (dimmed) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primary,
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = routeChoiceVerticalPadding()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RouteDot(if (selected) MaterialTheme.colorScheme.tertiaryContainer else Lavender, dimmed)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(row.title, style = MaterialTheme.typography.labelLarge)
                Text(row.detail, style = MaterialTheme.typography.bodyMedium)
            }
            routeTrailingLabel(row.action, selected)?.let {
                Text(stringResource(it), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// AND-27: the route rows grow with their text, so their top and bottom
// padding grows with it too. It is a share of the title font size, which
// already carries the user's font scale.
@Composable
private fun routeChoiceVerticalPadding(): Dp =
    with(LocalDensity.current) { MaterialTheme.typography.labelLarge.fontSize.toDp() } * RouteChoicePaddingPerTitleEm

private const val RouteChoicePaddingPerTitleEm = 0.75f

private fun routeTrailingLabel(action: RouteRowAction, selected: Boolean): Int? = when {
    selected -> R.string.active
    action == RouteRowAction.SignIn -> R.string.route_sign_in
    action == RouteRowAction.Select -> R.string.select
    else -> null
}

@Composable
private fun RouteDot(color: Color, dimmed: Boolean) {
    val shown = if (dimmed) color.copy(alpha = DisabledContentAlpha) else color
    Box(Modifier.size(14.dp).background(shown, CircleShape))
}

private fun routeShape(mode: ReplicationMode): Shape = RoundedCornerShape(
    topStart = if (mode == ReplicationMode.OFFLINE) 28.dp else 14.dp,
    topEnd = 14.dp,
    bottomStart = 14.dp,
    bottomEnd = if (mode == ReplicationMode.CENTRALIZED) 28.dp else 14.dp,
)

/** Material's disabled content emphasis. */
private const val DisabledContentAlpha = 0.38f
