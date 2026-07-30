package com.monolith.app.ui.importantpeople

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.monolith.app.domain.model.ImportantPerson
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportantPeopleScreen(
    onBack: () -> Unit,
    viewModel: ImportantPeopleViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.errors.collect { message -> scope.launch { snackbarHostState.showSnackbar(message) } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.important_people_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (!uiState.isLocked) {
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.important_people_add_cta))
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                stringResource(R.string.important_people_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )

            if (uiState.isLocked) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(12.dp),
                ) {
                    Text(
                        stringResource(R.string.important_people_locked_banner),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            if (uiState.people.isEmpty() && !uiState.isLoading) {
                Text(
                    stringResource(R.string.important_people_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }

            LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 80.dp)) {
                items(uiState.people, key = { it.packageName + it.name + it.handle }) { person ->
                    val app = uiState.installedApps.firstOrNull { it.packageName == person.packageName }
                    PersonRow(
                        person = person,
                        app = app,
                        isAppBlocked = person.packageName in uiState.blockedPackages,
                        enabled = !uiState.isLocked,
                        onRemove = { viewModel.removePerson(person) },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddPersonDialog(
            apps = uiState.blockableApps,
            onDismiss = { showAddDialog = false },
            onAdd = { packageName, name, handle ->
                viewModel.addPerson(packageName, name, handle)
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun PersonRow(
    person: ImportantPerson,
    app: AppInfo?,
    isAppBlocked: Boolean,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    val contentAlpha = if (isAppBlocked) 1f else 0.4f

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (app != null) {
            val bitmap = remember(app.packageName) { app.icon.toBitmap().asImageBitmap() }
            Image(
                bitmap = bitmap,
                contentDescription = null,
                alpha = contentAlpha,
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = listOfNotNull(person.name, person.handle?.let { "@$it" }).joinToString("  •  "),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
            )
            Text(
                text = if (isAppBlocked) {
                    app?.label ?: person.packageName
                } else {
                    "${app?.label ?: person.packageName} — not blocked"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
            )
        }
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.important_people_remove_cta))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddPersonDialog(
    apps: List<AppInfo>,
    onDismiss: () -> Unit,
    onAdd: (packageName: String, name: String?, handle: String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var selectedApp by remember { mutableStateOf<AppInfo?>(null) }
    var name by remember { mutableStateOf("") }
    var handle by remember { mutableStateOf("") }

    val canAdd = selectedApp != null && (name.isNotBlank() || handle.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.important_people_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = selectedApp?.label ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.important_people_app_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        apps.forEach { app ->
                            DropdownMenuItem(
                                text = { Text(app.label) },
                                onClick = {
                                    selectedApp = app
                                    expanded = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.important_people_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = handle,
                    onValueChange = { handle = it.removePrefix("@") },
                    label = { Text(stringResource(R.string.important_people_handle_label)) },
                    prefix = { Text("@") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.important_people_field_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = canAdd,
                onClick = { onAdd(selectedApp!!.packageName, name.ifBlank { null }, handle.ifBlank { null }) },
            ) {
                Text(stringResource(R.string.important_people_add_cta))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
