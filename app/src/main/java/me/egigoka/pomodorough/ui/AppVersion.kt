package me.egigoka.pomodorough.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.egigoka.pomodorough.BuildConfig
import me.egigoka.pomodorough.R

internal fun appVersionLabel(versionName: String): String = versionName.trim()

@Composable
internal fun VersionFooter(
    versionName: String = BuildConfig.VERSION_NAME,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(R.string.version_label, appVersionLabel(versionName)),
        modifier = modifier.fillMaxWidth().padding(top = 12.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
    )
}
