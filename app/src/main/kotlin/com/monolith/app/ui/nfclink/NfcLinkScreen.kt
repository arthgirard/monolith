package com.monolith.app.ui.nfclink

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.monolith.app.R
import com.monolith.app.domain.model.TagLinkMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfcLinkScreen(
    onBack: () -> Unit,
    onboardingStep: Pair<Int, Int>? = null,
    onboardingSubtitle: String? = null,
    viewModel: NfcLinkViewModel = hiltViewModel(),
) {
    val status by viewModel.status.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (onboardingStep != null) {
                            stringResource(R.string.onboarding_configuration_title)
                        } else {
                            stringResource(R.string.nfc_link_title)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (onboardingStep != null) {
                        Text(
                            stringResource(R.string.onboarding_step_indicator, onboardingStep.first, onboardingStep.second),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 16.dp),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (onboardingSubtitle != null) {
                Text(
                    onboardingSubtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                )
            }
            when (val current = status) {
                NfcLinkStatus.NfcUnsupported -> {
                    Text(
                        stringResource(R.string.nfc_not_supported),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }

                NfcLinkStatus.WaitingForTap -> {
                    Icon(
                        Icons.Filled.Nfc,
                        contentDescription = null,
                        modifier = Modifier.height(96.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        stringResource(R.string.nfc_link_instructions),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }

                NfcLinkStatus.Writing -> {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.height(24.dp))
                    Text(stringResource(R.string.nfc_link_writing), style = MaterialTheme.typography.bodyLarge)
                }

                is NfcLinkStatus.Success -> {
                    val message = if (current.mode == TagLinkMode.SMART_NDEF) {
                        stringResource(R.string.nfc_link_success_smart)
                    } else {
                        stringResource(R.string.nfc_link_success_fallback)
                    }
                    Text(message, style = MaterialTheme.typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.onboarding_continue))
                    }
                }

                is NfcLinkStatus.Error -> {
                    Text(
                        stringResource(R.string.nfc_link_error),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = viewModel::retry) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }
    }
}
