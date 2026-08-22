package com.monolith.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.monolith.app.ui.theme.MonolithShapes

/**
 * Monolith's snackbar. Material's default carries a 4dp corner and a tonal elevation that both
 * sit outside the app's geometry, and its action colour resolves to the baseline purple for a
 * scheme that never defines one. Flat, 12dp, amber action -- the same rules every card follows.
 */
@Composable
fun MonolithSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState, modifier = modifier) { data: SnackbarData ->
        Snackbar(
            snackbarData = data,
            shape = MonolithShapes.medium,
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            actionColor = MaterialTheme.colorScheme.secondary,
            dismissActionContentColor = MaterialTheme.colorScheme.inverseOnSurface,
        )
    }
}
