package com.monolith.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.monolith.app.R
import com.monolith.app.ui.theme.MonolithTheme
import kotlinx.coroutines.delay

private const val AUTO_ADVANCE_DELAY_MILLIS = 1800L

@Composable
fun OnboardingCompleteScreen(
    onFinished: () -> Unit,
    viewModel: NameViewModel = hiltViewModel(),
) {
    val name by viewModel.displayName.collectAsState()
    LaunchedEffect(Unit) {
        delay(AUTO_ADVANCE_DELAY_MILLIS)
        onFinished()
    }

    MonolithTheme(darkTheme = true) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.height(64.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    when (val current = name) {
                        // Blank for the moment the name takes to read, not the plain title flashing first.
                        null -> ""
                        "" -> stringResource(R.string.onboarding_complete_title)
                        else -> stringResource(R.string.onboarding_complete_title_named, current)
                    },
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.onboarding_complete_desc),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
