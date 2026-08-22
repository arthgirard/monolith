package com.monolith.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.monolith.app.R
import com.monolith.app.ui.theme.MonolithMotion
import com.monolith.app.ui.theme.Spacing

/**
 * A momentary state announcement -- a tag tap landing, an unlock granted -- built out of the
 * block wall's parts so the two read as one system: a slab of [MaterialTheme.colorScheme.surface]
 * over [MaterialTheme.colorScheme.background] ground, the wordmark signing the top, and every
 * line left-aligned on one vertical rule.
 *
 * These used to be a 72dp glyph and a label centred in the void, which shared nothing with the
 * wall but its background colour.
 *
 * The differences from the wall are deliberate and both come from the same fact -- this surface
 * has no action on it and leaves on its own after [MonolithMotion.FlashHoldMillis]:
 *  - The slab falls in [MonolithMotion.FlashDropMillis] rather than the wall's full drop, so it
 *    is settled and readable well before it has to go.
 *  - The ground stays empty. On the wall it carries the ways out; here there is nothing to press.
 *
 * The state glyph is kept, small and on the rule above the headline rather than as the centred
 * subject it used to be. In a surface this brief, being able to read the outcome from the shape
 * alone is worth more than the extra element costs.
 */
@Composable
fun MonolithFlash(
    icon: ImageVector,
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    statCaption: String? = null,
    statValue: String? = null,
    play: Boolean = true,
) {
    val description = listOfNotNull(headline, supporting, statCaption?.let { "$it $statValue" })
        .joinToString(". ")

    MonolithSlab(
        landed = false,
        modifier = modifier,
        dropMillis = MonolithMotion.FlashDropMillis,
        plinth = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Spacing.xxl)
                    .semantics(mergeDescendants = true) { contentDescription = description },
                verticalArrangement = Arrangement.Bottom,
            ) {
                // The wordmark gets a row to itself. Sharing one with the state glyph made the
                // glyph read as part of the logo lockup: two small squarish marks side by side,
                // and no way to tell which one was the brand.
                Image(
                    painterResource(R.drawable.ic_monolith_wordmark),
                    contentDescription = null,
                    modifier = Modifier
                        .height(28.dp)
                        .reveal(flashDelay(1, play)),
                )
                Spacer(Modifier.height(Spacing.xl))

                // The glyph trails the headline rather than leading it. Leading, it held the
                // left rule and pushed every line of type on this surface in by its own width
                // plus a gap -- the wall's rule is the one thing every screen here shares, and a
                // status glyph is not worth breaking it for. Trailing, the text starts where it
                // always starts and the glyph still sits on the line it describes.
                Row(
                    modifier = Modifier.reveal(flashDelay(2, play)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        headline,
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(Spacing.md))
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.height(GlyphSize),
                    )
                }
                if (supporting != null) {
                    Text(
                        supporting,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.reveal(flashDelay(3, play)),
                    )
                }

                // The rule only draws when it has something to separate. On a flash with no
                // figure to report it would be a line under nothing.
                if (statCaption != null && statValue != null) {
                    Spacer(Modifier.height(Spacing.xl))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(flashDelay(4, play))
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    Spacer(Modifier.height(Spacing.xl))
                    StatReadout(
                        caption = statCaption,
                        value = statValue,
                        modifier = Modifier.reveal(flashDelay(5, play)),
                    )
                }
            }
        },
        ground = {},
    )
}

/** The state glyph. Sized to sit beside the headline without competing with it. */
private val GlyphSize = 24.dp

/**
 * Below this a streak is not worth reporting: [formatDuration] floors to whole minutes, so a
 * ten-second streak would render as "0m" and read as a bug rather than as a short streak.
 */
const val MIN_REPORTABLE_STREAK_MILLIS: Long = 60_000L

/** One step of the flash's tighter stagger. */
@Composable
private fun flashDelay(step: Int, play: Boolean): Float =
    revealProgress(revealDelay(step, MonolithMotion.FlashStaggerMillis), play)
