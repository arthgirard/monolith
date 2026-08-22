package com.monolith.app.ui.bypass

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.monolith.app.R
import com.monolith.app.ui.components.MIN_REPORTABLE_STREAK_MILLIS
import com.monolith.app.ui.components.MonolithFlash
import com.monolith.app.util.formatDuration

/**
 * The block overlay's terminal moments. Both are the same kind of event NfcTapOverlayActivity
 * renders from its own side -- a state change announced, held briefly, then the screen handed
 * back -- so both go through [MonolithFlash] rather than each building their own centred column.
 * Keeping them on one composable is what stops the two surfaces drifting apart.
 */

/** A registered tag tapped mid-challenge turns Monolith off outright. */
@Composable
fun TagDisabledFeedback(bankedStreakMillis: Long, modifier: Modifier = Modifier) {
    MonolithFlash(
        icon = Icons.Filled.LockOpen,
        headline = stringResource(R.string.tap_overlay_unlocked),
        supporting = stringResource(R.string.tap_overlay_unlocked_body),
        statCaption = stringResource(R.string.overlay_stat_held)
            .takeIf { bankedStreakMillis >= MIN_REPORTABLE_STREAK_MILLIS },
        statValue = bankedStreakMillis.takeIf { it >= MIN_REPORTABLE_STREAK_MILLIS }?.let { formatDuration(it) },
        modifier = modifier,
    )
}

/**
 * The waiver was confirmed and the app is about to open. This moment had no UI at all before:
 * the screen cut straight from the waiver to the app, so the thing the user had just worked for
 * was never acknowledged.
 */
@Composable
fun UnlockGrantedFeedback(appLabel: String?, modifier: Modifier = Modifier) {
    MonolithFlash(
        icon = Icons.Filled.LockOpen,
        headline = stringResource(R.string.overlay_unlocked_granted),
        supporting = appLabel?.let { stringResource(R.string.overlay_unlocked_granted_body, it) },
        modifier = modifier,
    )
}
