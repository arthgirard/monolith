package com.monolith.app.ui.bypass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.monolith.app.R
import com.monolith.app.ui.components.MonolithSlab
import com.monolith.app.ui.components.StatReadout
import com.monolith.app.ui.components.reveal
import com.monolith.app.ui.components.revealDelay
import com.monolith.app.ui.components.revealProgress
import com.monolith.app.ui.theme.MonolithMotion
import com.monolith.app.ui.theme.DisabledAlpha
import com.monolith.app.ui.theme.Spacing
import com.monolith.app.ui.theme.tabular
import kotlinx.coroutines.delay

/**
 * The idle block screen: a slab naming the app that was reached for, what has been held so far,
 * and how many times today this same wall has been met -- over a ground carrying whatever ways
 * out the chosen strictness level leaves.
 *
 * The app's own label is the headline rather than "Monolith active". A wall that names itself is
 * interchangeable with any other blocker; one that names what you just reached for is about you.
 */
@Composable
fun BlockWall(
    wallState: BlockWallUiState,
    isLoading: Boolean,
    slabLanded: Boolean,
    onSlabLanded: () -> Unit,
    onGoHome: () -> Unit,
    onBreakCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val appLabel = wallState.appLabel
    // Nothing to show until the label resolves: the headline IS the label, and a wall that
    // flashed a placeholder first would undercut the point of naming the app at all.
    val play = appLabel != null
    val heldText = formatStreak(wallState.streakMillis)

    // A first open has nothing to report -- printing "1" is noise, and an emptier wall is calmer.
    val showOpens = wallState.opensToday > 1
    val wallDescription = when {
        appLabel == null -> ""
        showOpens -> stringResource(R.string.overlay_a11y_wall, appLabel, heldText, wallState.opensToday)
        else -> stringResource(R.string.overlay_a11y_wall_first, appLabel, heldText)
    }

    MonolithSlab(
        landed = slabLanded,
        modifier = modifier,
        onLanded = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onSlabLanded()
        },
        plinth = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Spacing.xxl)
                    // One node for the whole slab: element by element it reads as a scatter of
                    // fragments ("Instagram", "blocked", "Held", "4h 12m"); merged it reads as
                    // the sentence it actually is.
                    .semantics(mergeDescendants = true) { contentDescription = wallDescription },
                verticalArrangement = Arrangement.Bottom,
            ) {
                Image(
                    painterResource(R.drawable.ic_monolith_wordmark),
                    contentDescription = null,
                    // The full wordmark, not the bare mark: this is the one screen where the app
                    // has to say who is doing the blocking, and the amber dot inside the mark
                    // carries the accent the tinted glyph used to. Small on purpose -- it signs
                    // the wall, it isn't the subject. The app you reached for is.
                    modifier = Modifier
                        .height(28.dp)
                        .reveal(revealProgress(revealDelay(2), play)),
                )
                Spacer(Modifier.height(Spacing.xl))

                BypassEndedNotice(visible = wallState.bypassJustEnded && play)

                Text(
                    appLabel.orEmpty(),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.reveal(revealProgress(revealDelay(2), play)),
                )
                Text(
                    stringResource(R.string.overlay_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.reveal(revealProgress(revealDelay(3), play)),
                )

                Spacer(Modifier.height(Spacing.xl))
                Hairline(progress = revealProgress(revealDelay(4), play))
                Spacer(Modifier.height(Spacing.xl))

                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxl)) {
                    StatReadout(
                        caption = stringResource(R.string.overlay_stat_held),
                        value = heldText,
                        modifier = Modifier.reveal(revealProgress(revealDelay(5), play)),
                    )
                    if (showOpens) {
                        StatReadout(
                            caption = stringResource(R.string.overlay_stat_opened),
                            value = wallState.opensToday.toString(),
                            modifier = Modifier.reveal(revealProgress(revealDelay(6), play)),
                        )
                    }
                }
            }
        },
        ground = {
            // Progress belongs to the structure, not to a spinner floating in the middle of it:
            // a hairline along the slab's bottom edge, exactly where the plinth meets the ground.
            LoadingEdge(visible = isLoading)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .reveal(revealProgress(revealDelay(7), play)),
                // The ways out are not peers and should not sit in one stack. The escape
                // hatch, where the level allows one, rides directly under the slab's bottom edge -- attached to the mass, and
                // read immediately after the streak it would destroy -- while "go home" is
                // pinned to the bottom, where the thumb already is. Putting them adjacent gave
                // the most reachable inch of the screen to the escape hatch, which is backwards.
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                if (wallState.canBreakCode) {
                    WallAction(
                        label = stringResource(R.string.overlay_break_code_title),
                        suffix = stringResource(R.string.overlay_break_code_subtitle),
                        muted = true,
                        enabled = !isLoading,
                        onClick = onBreakCode,
                    )
                } else {
                    // Nothing greyed out in its place. A disabled escape hatch is still an escape
                    // hatch to look at, and the level the user picked was the one where there
                    // isn't one -- SpaceBetween then drops "go home" to the bottom on its own.
                    Spacer(Modifier.height(0.dp))
                }
                Column {
                    WallDivider()
                    WallAction(
                        label = stringResource(R.string.overlay_go_home),
                        leading = Icons.AutoMirrored.Filled.ArrowForward,
                        onClick = onGoHome,
                    )
                }
            }
        },
    )
}

/**
 * One line explaining where the bypass went. Without it the overlay simply reappears fifteen
 * minutes after a bypass started and the user is left to infer why.
 */
@Composable
private fun BypassEndedNotice(visible: Boolean) {
    // Self-retiring: this explains a transition, and a transition explained permanently stops
    // being a transition and starts being part of the wall.
    var shown by remember { mutableStateOf(true) }
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        shown = true
        delay(BYPASS_NOTICE_HOLD_MILLIS)
        shown = false
    }
    AnimatedVisibility(
        visible = visible && shown,
        enter = fadeIn(tween(MonolithMotion.ContentFadeMillis)),
        exit = fadeOut(tween(MonolithMotion.ContentFadeMillis)),
    ) {
        Column {
            Text(
                stringResource(R.string.overlay_bypass_ended),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.height(Spacing.sm))
        }
    }
}

/** The rule the wall's identity sits on. Draws itself open rather than appearing whole. */
@Composable
private fun Hairline(progress: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth(progress)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun LoadingEdge(visible: Boolean) {
    if (!visible) return
    LinearProgressIndicator(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp),
        color = MaterialTheme.colorScheme.secondary,
        trackColor = MaterialTheme.colorScheme.background,
    )
}

/** Full-bleed hairline. The same rule the plinth draws above its stats, continued into the ground. */
@Composable
private fun WallDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * One way out of the wall, as a full-bleed row rather than a button.
 *
 * The wall is built entirely from horizontal bands -- the plinth, its hairline, the stats row --
 * and a pill floating at its own width in the middle of the ground was the one element not built
 * that way. Rows carry the same Spacing.xxl inset as the plinth's text, so every piece of type on
 * this screen sits on one vertical rule: wordmark, app label, "blocked", the stat captions, and
 * both action labels.
 *
 * [leading] is the one exception to that rule, and only "go home" takes it: a bare line of text
 * at the bottom of the screen did not read as the thing to press. The arrow itself sits on the
 * rule, so the row still starts where everything above it starts -- only its label is carried
 * across. The escape hatch gets no glyph, which widens the gap between the two.
 *
 * [muted] keeps the escape hatch subordinate to "go home" through weight and colour rather than
 * through a different shape: smaller type, onSurfaceVariant instead of onSurface, and no rule of
 * its own. Placement carries the rest of the hierarchy -- see the ground's arrangement, where the
 * two actions are deliberately separated rather than stacked.
 */
@Composable
private fun WallAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    suffix: String? = null,
    muted: Boolean = false,
    enabled: Boolean = true,
) {
    val base = if (muted) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val color = base.copy(alpha = if (enabled) 1f else DisabledAlpha)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.xxl, vertical = Spacing.lg),
    ) {
        if (leading != null) {
            Icon(
                leading,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(Spacing.md))
        }
        Text(
            label,
            style = if (muted) {
                MaterialTheme.typography.bodyMedium
            } else {
                // Medium weight rather than regular: this is the action the wall wants taken,
                // and it has no fill or border left to carry that.
                MaterialTheme.typography.titleMedium
            },
            color = color,
        )
        if (suffix != null) {
            Text(
                suffix,
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = color,
            )
        }
    }
}

/**
 * Seconds below an hour, minutes above it. The wall's one live number should visibly move --
 * a readout that sits still for a whole minute reads as a static label rather than a streak
 * still running. Tabular figures (see [tabular]) keep it from reflowing as digits change.
 */
private fun formatStreak(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds.toString().padStart(2, '0')}s"
        // No leading "0m". A streak of under a minute is seconds, and "0m 49s" reads as a
        // formatter that forgot to handle its own smallest case.
        else -> "${seconds}s"
    }
}

private const val BYPASS_NOTICE_HOLD_MILLIS = 3000L
