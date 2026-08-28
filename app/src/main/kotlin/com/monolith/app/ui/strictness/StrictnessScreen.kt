package com.monolith.app.ui.strictness

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.monolith.app.R
import com.monolith.app.domain.model.StrictnessLevel
import com.monolith.app.ui.theme.DisabledAlpha
import com.monolith.app.ui.theme.MonolithButtonShape

/**
 * The strictness choice, shown as the last onboarding step and reachable afterwards from
 * Settings. One composable for both, the way [com.monolith.app.ui.appselector.AppSelectorScreen]
 * and [com.monolith.app.ui.nfclink.NfcLinkScreen] already serve their two callers.
 *
 * Every level is spelled out in full rather than named and left to be discovered. "Code breaker"
 * means nothing to someone who has not hit the wall yet, and this is the one screen where the
 * cost of each door has to be legible before it is chosen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StrictnessScreen(
    onBack: () -> Unit,
    onboardingStep: Pair<Int, Int>? = null,
    onContinue: (() -> Unit)? = null,
    viewModel: StrictnessViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (onboardingStep != null) {
                            stringResource(R.string.onboarding_configuration_title)
                        } else {
                            stringResource(R.string.strictness_title)
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (uiState.isLocked) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(12.dp),
                ) {
                    Text(
                        stringResource(R.string.settings_locked_banner),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(R.string.strictness_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                StrictnessLevel.entries.forEach { level ->
                    LevelCard(
                        level = level,
                        selected = level == uiState.level,
                        enabled = !uiState.isLocked,
                        onClick = { viewModel.select(level) },
                    )
                }

                // Said here rather than left for someone to discover. A screen offering a level
                // called Absolute has to be honest about what it cannot hold, or the first person
                // who works out that Monolith uninstalls like any other app learns that the rest
                // of the screen was overselling too.
                Text(
                    stringResource(R.string.strictness_uninstall_caveat),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (onContinue != null) {
                Button(
                    shape = MonolithButtonShape,
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
                ) {
                    Text(stringResource(R.string.onboarding_continue))
                }
            }
        }
    }
}

@Composable
private fun LevelCard(
    level: StrictnessLevel,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val alpha = if (enabled) 1f else DisabledAlpha
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface, shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(20.dp),
    ) {
        Icon(
            if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) {
                MaterialTheme.colorScheme.secondary.copy(alpha = alpha)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
            },
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                stringResource(level.labelRes),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(level.descriptionRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
        }
    }
}

/** The screen's own copy for a level, kept out of the domain model. */
val StrictnessLevel.labelRes: Int
    get() = when (this) {
        StrictnessLevel.STANDARD -> R.string.strictness_standard_title
        StrictnessLevel.STRICT -> R.string.strictness_strict_title
        StrictnessLevel.ABSOLUTE -> R.string.strictness_absolute_title
    }

val StrictnessLevel.descriptionRes: Int
    get() = when (this) {
        StrictnessLevel.STANDARD -> R.string.strictness_standard_desc
        StrictnessLevel.STRICT -> R.string.strictness_strict_desc
        StrictnessLevel.ABSOLUTE -> R.string.strictness_absolute_desc
    }
