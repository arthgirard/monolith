package com.monolith.app.ui.bypass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import com.monolith.app.R
import com.monolith.app.ui.theme.DisabledAlpha
import com.monolith.app.ui.theme.MonolithButtonShape
import com.monolith.app.ui.theme.MonolithMotion
import com.monolith.app.ui.theme.MonolithShapes
import com.monolith.app.ui.theme.Spacing

/**
 * The last step: copy the sentence out by hand.
 *
 * The sentence itself stays upper case. That contradicts the app's "no all-caps" rule everywhere
 * else, and deliberately so -- this is a copy-typing target rather than display type, and caps
 * defeat word-shape recognition, forcing the character-by-character reading that is the whole
 * mechanism of the friction. It is quarantined to this panel.
 */
@Composable
fun WaiverStep(
    sentence: String,
    input: String,
    matches: Boolean,
    onInputChange: (String) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Where the typing stopped agreeing with the sentence. Everything before it is done; the
    // character at it is the mistake. Used both to shade the panel and to decide whether the
    // field is in error, so a single typo doesn't paint the entire input red.
    val divergence = remember(sentence, input) { firstDivergence(sentence, input) }

    // The code broke. Nothing else in this flow lands, and arriving at the last step with no
    // acknowledgement makes solving the puzzle feel like nothing happened.
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(Unit) { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.waiver_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.xl))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MonolithShapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(Spacing.lg),
        ) {
            Text(
                progressShaded(sentence, typedCount = divergence ?: input.length),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Spacer(Modifier.height(Spacing.xl))
        // No paste: the point is typing the sentence out, not lifting it in from the clipboard.
        // Hiding the selection toolbar removes the visible Paste action; rejecting any
        // onValueChange that inserts more than one character in one shot catches paste attempts
        // that don't go through the toolbar (external keyboard shortcuts, IME word-commit,
        // drag-and-drop) without blocking normal one-key-at-a-time typing or deletes.
        val noToolbar = remember {
            object : TextToolbar {
                override val status: TextToolbarStatus = TextToolbarStatus.Hidden
                override fun showMenu(
                    rect: Rect,
                    onCopyRequested: (() -> Unit)?,
                    onPasteRequested: (() -> Unit)?,
                    onCutRequested: (() -> Unit)?,
                    onSelectAllRequested: (() -> Unit)?,
                ) = Unit
                override fun hide() = Unit
            }
        }
        CompositionLocalProvider(LocalTextToolbar provides noToolbar) {
            OutlinedTextField(
                value = input,
                onValueChange = { newText -> if (newText.length - input.length <= 1) onInputChange(newText) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.waiver_placeholder)) },
                keyboardOptions = KeyboardOptions(
                    autoCorrect = false,
                    capitalization = KeyboardCapitalization.None,
                ),
                // Error only once the typing has actually left the sentence, not for every
                // moment the input is merely incomplete.
                isError = divergence != null,
                singleLine = false,
            )
        }
        Spacer(Modifier.height(Spacing.xl))
        AnimatedVisibility(
            visible = matches,
            enter = fadeIn(tween(MonolithMotion.ContentFadeMillis)) + expandVertically(),
        ) {
            OutlinedButton(onClick = onContinue, shape = MonolithButtonShape) {
                Text(stringResource(R.string.waiver_continue))
            }
        }
    }
}

/**
 * The sentence with the part already typed at full strength and the rest faded back. Copying a
 * long block of upper-case text without this means diffing two paragraphs by eye to find your
 * place; the shading just shows where you are.
 */
@Composable
private fun progressShaded(sentence: String, typedCount: Int): AnnotatedString {
    val done = typedCount.coerceIn(0, sentence.length)
    val onPanel = MaterialTheme.colorScheme.onSurface
    val remaining = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DisabledAlpha)
    return buildAnnotatedString {
        withStyle(SpanStyle(color = onPanel)) { append(sentence.substring(0, done)) }
        withStyle(SpanStyle(color = remaining)) { append(sentence.substring(done)) }
    }
}

/**
 * Index of the first character where [input] stops matching [sentence], or null while the input
 * is still a correct prefix of it (including empty, and including complete).
 */
private fun firstDivergence(sentence: String, input: String): Int? {
    if (input.length > sentence.length) return sentence.length
    for (i in input.indices) {
        if (input[i] != sentence[i]) return i
    }
    return null
}
