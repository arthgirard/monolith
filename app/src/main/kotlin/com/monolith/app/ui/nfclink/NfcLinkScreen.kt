package com.monolith.app.ui.nfclink

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.monolith.app.R
import com.monolith.app.domain.model.TagLinkMode
import com.monolith.app.ui.theme.MonolithButtonShape
import com.monolith.app.util.formatDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfcLinkScreen(
    onBack: () -> Unit,
    onboardingStep: Pair<Int, Int>? = null,
    onboardingSubtitle: String? = null,
    onSkip: (() -> Unit)? = null,
    onLinked: (() -> Unit)? = null,
    offersRestore: Boolean = false,
    onRestored: (() -> Unit)? = null,
    viewModel: NfcLinkViewModel = hiltViewModel(),
) {
    val status by viewModel.status.collectAsState()
    val context = LocalContext.current
    var confirmFresh by remember { mutableStateOf(false) }
    SideEffect { viewModel.offersRestore = offersRestore }
    LaunchedEffect(status) {
        val restored = status as? NfcLinkStatus.Restored ?: return@LaunchedEffect
        // A backup brings the whole setup back; friends alone still need it walked through.
        if (restored.hasBackup) onRestored?.invoke() else onLinked?.invoke()
        viewModel.restoreHandled()
    }

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
        ) {
            if (onboardingSubtitle != null) {
                Text(
                    onboardingSubtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                            Text(
                                message,
                                style = MaterialTheme.typography.titleLarge,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            if (current.carriesCode) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    stringResource(R.string.nfc_link_carries_code),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                            Spacer(Modifier.height(24.dp))
                            Button(onClick = onLinked ?: onBack, modifier = Modifier.fillMaxWidth(), shape = MonolithButtonShape) {
                                Text(stringResource(R.string.onboarding_continue))
                            }
                        }

                        NfcLinkStatus.Checking, NfcLinkStatus.Restoring, is NfcLinkStatus.Restored -> {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.height(24.dp))
                            Text(
                                stringResource(
                                    if (current == NfcLinkStatus.Checking) R.string.tag_restore_checking else R.string.tag_restore_restoring,
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }

                        is NfcLinkStatus.OfferRestore -> {
                            Text(
                                stringResource(R.string.tag_restore_title),
                                style = MaterialTheme.typography.titleLarge,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (current.hasBackup) {
                                    stringResource(R.string.tag_restore_body, current.backupAt?.let { formatDateTime(context, it) }.orEmpty())
                                } else {
                                    stringResource(R.string.tag_restore_friends_body)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            Spacer(Modifier.height(24.dp))
                            Button(onClick = viewModel::restore, modifier = Modifier.fillMaxWidth(), shape = MonolithButtonShape) {
                                Text(stringResource(R.string.tag_restore_cta))
                            }
                            TextButton(onClick = viewModel::startFresh, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.tag_start_fresh))
                            }
                        }

                        NfcLinkStatus.CheckFailed -> {
                            Text(
                                stringResource(R.string.tag_check_failed),
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = viewModel::tryAgain, shape = MonolithButtonShape) {
                                Text(stringResource(R.string.tag_try_again))
                            }
                            // The code on the tag could not be checked: overwriting it may lose the only way back.
                            TextButton(onClick = { confirmFresh = true }) {
                                Text(stringResource(R.string.tag_start_fresh))
                            }
                        }

                        NfcLinkStatus.Unreadable -> {
                            Text(
                                stringResource(R.string.backup_restore_unreadable),
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            Spacer(Modifier.height(16.dp))
                            TextButton(onClick = viewModel::startFresh) {
                                Text(stringResource(R.string.tag_start_fresh))
                            }
                        }

                        NfcLinkStatus.TapAgain -> {
                            Icon(
                                Icons.Filled.Nfc,
                                contentDescription = null,
                                modifier = Modifier.height(96.dp),
                                tint = MaterialTheme.colorScheme.secondary,
                            )
                            Spacer(Modifier.height(24.dp))
                            Text(
                                stringResource(R.string.tag_tap_again),
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }

                        NfcLinkStatus.Locked -> {
                            Text(
                                stringResource(R.string.nfc_link_locked),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }

                        is NfcLinkStatus.Error -> {
                            Text(
                                stringResource(R.string.nfc_link_error),
                                style = MaterialTheme.typography.bodyLarge,
                                // Not `error`: that role is the bypass button's fill and stays the
                                // deep red, which only manages 3:1 as text on the dark ground.
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = viewModel::retry, shape = MonolithButtonShape) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    }
                }
            }

            val settled = status is NfcLinkStatus.Success || status is NfcLinkStatus.Restored || status == NfcLinkStatus.Restoring
            if (onSkip != null && !settled) {
                Button(onClick = onSkip, modifier = Modifier.fillMaxWidth(), shape = MonolithButtonShape) {
                    Text(stringResource(R.string.not_now_cta))
                }
            }
        }
    }

    if (confirmFresh) {
        AlertDialog(
            onDismissRequest = { confirmFresh = false },
            text = { Text(stringResource(R.string.tag_start_fresh_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmFresh = false
                    viewModel.startFresh()
                }) { Text(stringResource(R.string.tag_start_fresh)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmFresh = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
