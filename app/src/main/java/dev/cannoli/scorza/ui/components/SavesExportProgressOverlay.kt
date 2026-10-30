package dev.cannoli.scorza.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cannoli.scorza.R
import dev.cannoli.ui.components.CannoliProgressBar
import dev.cannoli.ui.components.screenPadding
import dev.cannoli.ui.theme.LocalCannoliTypography
import dev.cannoli.ui.theme.Spacing

/** Full-screen progress while Export Saves writes its zip, in the style of the update download screen. */
@Composable
fun SavesExportProgressOverlay(done: Int, total: Int) {
    val typo = LocalCannoliTypography.current
    val fraction = if (total > 0) done.toFloat() / total else 0f
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = screenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.saves_exporting),
                style = typo.titleLarge.copy(color = Color.White),
            )
            Spacer(modifier = Modifier.height(Spacing.Lg))
            CannoliProgressBar(progress = fraction)
            Spacer(modifier = Modifier.height(Spacing.Sm))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (total > 0) stringResource(R.string.saves_export_progress, done, total) else "",
                    style = typo.labelSmall.copy(color = Color.White),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${(fraction * 100).toInt()}%",
                    style = typo.labelSmall.copy(color = Color.White),
                )
            }
        }
    }
}
