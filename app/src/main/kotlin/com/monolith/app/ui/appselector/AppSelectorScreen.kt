package com.monolith.app.ui.appselector

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import com.monolith.app.R
import com.monolith.app.domain.model.AppInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSelectorScreen(
    onBack: () -> Unit,
    viewModel: AppSelectorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showDumbPhoneConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_selector_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (uiState.isLocked) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(12.dp),
                ) {
                    Text(
                        stringResource(R.string.app_selector_locked_banner),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            OutlinedTextField(
                value = uiState.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                placeholder = { Text(stringResource(R.string.app_selector_search_hint)) },
                singleLine = true,
                enabled = !uiState.isLocked,
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = viewModel::toggleSelectAll,
                    enabled = !uiState.isLocked,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        stringResource(
                            if (uiState.allSelected) R.string.deselect_all_cta else R.string.select_all_cta,
                        ),
                    )
                }
                OutlinedButton(
                    onClick = { showDumbPhoneConfirm = true },
                    enabled = !uiState.isLocked,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.dumb_phone_mode_cta))
                }
            }

            Spacer(Modifier.height(8.dp))

            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(uiState.filteredApps, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        isChecked = app.packageName in uiState.blockedPackages,
                        enabled = !uiState.isLocked,
                        onToggle = { viewModel.toggleApp(app.packageName) },
                    )
                }
            }
        }
    }

    if (showDumbPhoneConfirm) {
        AlertDialog(
            onDismissRequest = { showDumbPhoneConfirm = false },
            title = { Text(stringResource(R.string.dumb_phone_mode_confirm_title)) },
            text = { Text(stringResource(R.string.dumb_phone_mode_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.applyDumbPhoneMode()
                    showDumbPhoneConfirm = false
                }) {
                    Text(stringResource(R.string.dumb_phone_mode_confirm_start))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDumbPhoneConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun AppRow(
    app: AppInfo,
    isChecked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val bitmap = remember(app.packageName) { app.icon.toBitmap().asImageBitmap() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
        )
        Text(
            text = app.label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Checkbox(
            checked = isChecked,
            onCheckedChange = { onToggle() },
            enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.secondary),
        )
    }
}
