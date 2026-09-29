package com.monolith.app.ui.friends

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.monolith.app.R
import com.monolith.app.domain.model.SharedApp
import com.monolith.app.ui.components.SettingsDivider
import com.monolith.app.ui.theme.mono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val IconSize = 36.dp

/** One member's blocked apps, as they name them. Opened from their row on the board. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedAppsScreen(onBack: () -> Unit, viewModel: FriendsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val member = uiState.appsOf
    val apps = member?.blockedApps
    // Restored after the process died, the board's row is gone: nothing to show but the way back.
    LaunchedEffect(apps == null) { if (apps == null) onBack() }
    if (member == null || apps == null) return

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(member.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            item(key = "header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    SectionHeader(stringResource(R.string.friends_apps_heading))
                    Text(
                        apps.size.toString(),
                        style = MaterialTheme.typography.labelLarge.mono(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            if (apps.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.friends_apps_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // A lazy list can't sit inside SettingsGroup, so each row draws its own share of the card.
            itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
                val top = if (index == 0) 16.dp else 0.dp
                val bottom = if (index == apps.lastIndex) 16.dp else 0.dp
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom))
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    AppRow(app)
                    if (index > 0) Box(Modifier.align(Alignment.TopStart)) { SettingsDivider(startInset = 20.dp + IconSize + 16.dp) }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: SharedApp) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppIcon(app)
        Text(appLabel(app), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Lists uploaded before labels were checked can carry a bare package name; this phone's own name
 * for the app replaces it when the app is installed here.
 */
@Composable
private fun appLabel(app: SharedApp): String {
    if (app.label != app.packageName) return app.label
    val context = LocalContext.current
    val local by produceState<String?>(null, app.packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(app.packageName, 0)).toString().trim()
            }.getOrNull()
        }
    }
    return local?.takeIf { it.isNotEmpty() } ?: app.label
}

/** The icon from this phone when the app is installed here too, else its initial on a tile. */
@Composable
private fun AppIcon(app: SharedApp) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { IconSize.roundToPx() }
    val icon by produceState<ImageBitmap?>(null, app.packageName, sizePx) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(app.packageName).toBitmap(sizePx, sizePx).asImageBitmap() }.getOrNull()
        }
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(IconSize))
    } else {
        Box(
            modifier = Modifier
                .size(IconSize)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                app.label.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
