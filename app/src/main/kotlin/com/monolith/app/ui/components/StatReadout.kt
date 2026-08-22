package com.monolith.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import com.monolith.app.ui.theme.tabular

/**
 * The app's data unit: a muted caption over a figure. Already the shape of the Time Saved
 * header and the Home card's today total; extracted here so the block overlay's stats are
 * literally the same component rather than a third hand-built copy of it.
 *
 * The value is always set in tabular figures -- these are numbers that tick.
 */
@Composable
fun StatReadout(
    caption: String,
    value: String,
    modifier: Modifier = Modifier,
    valueStyle: TextStyle = MaterialTheme.typography.headlineSmall,
) {
    Column(modifier = modifier) {
        Text(
            caption,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = valueStyle.tabular(),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
