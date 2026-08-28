package com.monolith.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.monolith.app.ui.theme.DisabledAlpha

/**
 * The panel Home and Settings both use for lists of places to go.
 *
 * One surface rather than a stack of outlined buttons: these are destinations, not actions, and
 * as full-width buttons the screen read as a form.
 */
@Composable
fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface),
        content = content,
    )
}

/**
 * A row in [SettingsGroup]. [value] is the setting's current state where it has one, shown
 * before the chevron.
 *
 * A disabled row keeps its place rather than disappearing: a list that changes shape depending
 * on whether Monolith is running hides the setting from the people still learning it exists,
 * and reads as a bug rather than as the lock it is. The banner above the group says why.
 */
@Composable
fun SettingsRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    value: String? = null,
) {
    val contentAlpha = if (enabled) 1f else DisabledAlpha
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
        )
        Spacer(Modifier.weight(1f))
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
            )
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Inset past the icon column, so the rows read as one list rather than stacked slices. */
@Composable
fun SettingsDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(start = 54.dp),
    )
}
