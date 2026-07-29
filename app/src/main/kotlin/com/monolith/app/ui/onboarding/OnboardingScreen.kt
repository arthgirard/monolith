package com.monolith.app.ui.onboarding

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.hilt.navigation.compose.hiltViewModel
import com.monolith.app.R

@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Text(
                text = stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.headlineLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.onboarding_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            RestrictedSettingsHint(
                onOpenAppInfo = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                },
            )

            Spacer(Modifier.height(24.dp))

            PermissionCard(
                title = stringResource(R.string.perm_usage_title),
                description = stringResource(R.string.perm_usage_desc),
                granted = uiState.usageAccessGranted,
                onGrant = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
            )
            Spacer(Modifier.height(16.dp))
            PermissionCard(
                title = stringResource(R.string.perm_overlay_title),
                description = stringResource(R.string.perm_overlay_desc),
                granted = uiState.overlayGranted,
                onGrant = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                },
            )
            Spacer(Modifier.height(16.dp))
            PermissionCard(
                title = stringResource(R.string.perm_accessibility_title),
                description = stringResource(R.string.perm_accessibility_desc),
                granted = uiState.accessibilityGranted,
                onGrant = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            )
            Spacer(Modifier.height(16.dp))
            PermissionCard(
                title = stringResource(R.string.perm_notifications_title),
                description = stringResource(R.string.perm_notifications_desc),
                granted = uiState.notificationAccessGranted,
                onGrant = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = onFinished,
                enabled = uiState.allGranted,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.onboarding_finish))
            }
        }
    }
}

@Composable
private fun RestrictedSettingsHint(onOpenAppInfo: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.restricted_settings_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.restricted_settings_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onOpenAppInfo) {
                Text(stringResource(R.string.restricted_settings_open))
            }
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    granted: Boolean,
    onGrant: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (granted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            if (!granted) {
                OutlinedButton(onClick = onGrant) {
                    Text(stringResource(R.string.perm_grant))
                }
            }
        }
    }
}
