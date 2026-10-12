package me.egigoka.pomodorough.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import me.egigoka.pomodorough.BuildConfig
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.crash.CrashReportingConsent
import me.egigoka.pomodorough.crash.CrashReportingRuntime

@Composable
internal fun CrashReportingCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    var checked by remember { mutableStateOf(CrashReportingConsent.isEnabled(context)) }
    val name = stringResource(R.string.crash_reporting_title)
    val state = stringResource(if (checked) R.string.switch_on else R.string.switch_off)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.testTag("crash_reporting_card"),
    ) {
        Row(
            modifier = Modifier
                .toggleable(value = checked, role = Role.Switch, onValueChange = {
                    checked = it
                    applyCrashChoice(context, it)
                })
                .semantics(mergeDescendants = true) {
                    contentDescription = name
                    stateDescription = state
                }
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.crash_reporting_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(stringResource(R.string.crash_reporting_body), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = null, modifier = Modifier.testTag("crash_reporting_switch"))
        }
    }
}

private fun applyCrashChoice(context: android.content.Context, checked: Boolean) {
    // A76: opt-out takes effect immediately (no restart):
    // Sentry.close() stops errors + session replay; re-init
    // on re-enable restores scrubbed reporting.
    // R48-A05: re-enable shares launch eligibility; suppressed stays stopped.
    CrashReportingConsent.setEnabled(context, checked)
    CrashReportingRuntime.applyConsent(
        enabled = checked,
        onEnable = {
            if (!CrashReportingRuntime.isEligible(BuildConfig.SENTRY_DSN, true)) {
                CrashReportingRuntime.stop()
            } else {
                CrashReportingRuntime.start(context, BuildConfig.SENTRY_DSN, "${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}")
            }
        },
        onDisable = { CrashReportingRuntime.stop() },
    )
}
