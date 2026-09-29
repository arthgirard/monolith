package com.monolith.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.monolith.app.R
import com.monolith.app.domain.model.DISPLAY_NAME_MAX_LENGTH
import com.monolith.app.domain.model.isValidDisplayName
import com.monolith.app.ui.theme.MonolithButtonShape

/**
 * The first configuration step: what friends will see. Asked here, once, so that creating or
 * joining a group later is one tap instead of a form.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NameScreen(
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onboardingStep: Pair<Int, Int>,
    viewModel: NameViewModel = hiltViewModel(),
) {
    val stored by viewModel.displayName.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val failed by viewModel.failed.collectAsState()
    var name by rememberSaveable { mutableStateOf("") }
    var prefilled by rememberSaveable { mutableStateOf(false) }
    val valid = isValidDisplayName(name)
    val submit = { if (valid) viewModel.save(name, onSaved = onContinue) }

    // Coming back to this step shows what was saved, once, without overwriting what's typed.
    LaunchedEffect(stored) {
        val current = stored ?: return@LaunchedEffect
        if (!prefilled) {
            if (name.isEmpty()) name = current
            prefilled = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.onboarding_configuration_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    Text(
                        stringResource(R.string.onboarding_step_indicator, onboardingStep.first, onboardingStep.second),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(R.string.onboarding_name_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(DISPLAY_NAME_MAX_LENGTH) },
                    label = { Text(stringResource(R.string.friends_name_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.name_caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (failed) {
                    Text(stringResource(R.string.friends_error_generic), color = MaterialTheme.colorScheme.error)
                }
            }

            Button(
                shape = MonolithButtonShape,
                onClick = submit,
                enabled = valid && !busy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            ) {
                Text(stringResource(R.string.onboarding_continue))
            }
        }
    }
}
