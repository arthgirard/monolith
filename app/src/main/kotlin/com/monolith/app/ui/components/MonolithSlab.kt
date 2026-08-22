package com.monolith.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.monolith.app.ui.theme.MonolithMotion
import com.monolith.app.ui.theme.MonolithSettle
import com.monolith.app.ui.theme.rememberAnimationsEnabled
import kotlin.math.roundToInt

/**
 * The block overlay's structure: a full-bleed slab of [MaterialTheme.colorScheme.surface] that
 * falls from above and comes to rest against the top of the screen, with the remaining
 * [MaterialTheme.colorScheme.background] below it as ground.
 *
 * Why a slab and not a centred column: the wall is the one surface in Monolith that interrupts
 * rather than being visited, and it should read as mass arriving. The drop is legible without
 * ever exposing the app underneath, because BlockOverlayGuard has already painted the ground
 * colour opaquely by the time this composes -- the slab falls onto a covered screen, not onto
 * the blocked app.
 *
 * @param landed true when the slab is already down and must not re-drop. The service can
 *   retarget a live overlay at a second blocked app; replaying the fall there would read as a
 *   new interruption rather than the same wall changing what it names.
 * @param plinthHeightFraction the slab's share of the screen, or null to size it to its content.
 *   Null is for the puzzle and waiver, which are taller than a fixed slab and scroll.
 */
@Composable
fun MonolithSlab(
    landed: Boolean,
    modifier: Modifier = Modifier,
    plinthHeightFraction: Float? = 0.58f,
    dropMillis: Int = MonolithMotion.SlabDropMillis,
    onLanded: () -> Unit = {},
    plinth: @Composable ColumnScope.() -> Unit,
    ground: @Composable ColumnScope.() -> Unit,
) {
    val animationsEnabled = rememberAnimationsEnabled()
    var plinthHeightPx by remember { mutableIntStateOf(0) }
    val drop = remember { Animatable(if (landed || !animationsEnabled) 1f else 0f) }

    LaunchedEffect(Unit) {
        if (drop.value == 1f) return@LaunchedEffect
        drop.animateTo(1f, tween(dropMillis, easing = MonolithSettle))
        onLanded()
    }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (plinthHeightFraction != null) {
                        Modifier.fillMaxHeight(plinthHeightFraction)
                    } else {
                        Modifier
                    },
                )
                .onSizeChanged { plinthHeightPx = it.height }
                // Offset rather than a translation on the whole column: the slab's layout slot
                // stays reserved while it falls, so the ground content below doesn't slide with
                // it and then jump into place at the end.
                .offset { IntOffset(0, -((1f - drop.value) * plinthHeightPx).roundToInt()) }
                .background(MaterialTheme.colorScheme.surface)
                .clipToBounds(),
            content = plinth,
        )
        ground()
    }
}

/**
 * Progress of one staggered element's arrival, 0 to 1, after [delayMillis]. Returns 1 straight
 * away when animations are off system-wide: a 40ms version of this reads worse than none.
 */
@Composable
fun revealProgress(delayMillis: Int, play: Boolean = true): Float {
    val animationsEnabled = rememberAnimationsEnabled()
    // Starts hidden regardless of [play]'s first value. Seeding from `play` instead would break
    // the common case outright: the wall's content waits on an app label that resolves a frame
    // or two late, so play is false on first composition, and a progress seeded to 1 there would
    // have nothing left to animate by the time the label arrived.
    val progress = remember { Animatable(if (animationsEnabled) 0f else 1f) }
    LaunchedEffect(play, animationsEnabled) {
        if (!play) return@LaunchedEffect
        if (animationsEnabled) {
            progress.animateTo(
                1f,
                tween(MonolithMotion.ContentFadeMillis, delayMillis = delayMillis),
            )
        } else {
            progress.snapTo(1f)
        }
    }
    return progress.value
}

/**
 * Fade plus a short rise. The rise is small on purpose -- content settling under the slab, not
 * sliding in from off-screen.
 */
fun Modifier.reveal(progress: Float, rise: Dp = 12.dp): Modifier = graphicsLayer {
    alpha = progress
    translationY = (1f - progress) * rise.toPx()
}

/** The stagger step, expressed as a multiple so call sites read as an order rather than as times. */
fun revealDelay(step: Int, stepMillis: Int = MonolithMotion.StaggerMillis): Int = step * stepMillis
