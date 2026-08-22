package com.monolith.app.ui.bypass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.monolith.app.R
import com.monolith.app.domain.model.CodeBreaker
import com.monolith.app.domain.model.SlotResult
import com.monolith.app.domain.usecase.CodeBreakerGenerator
import com.monolith.app.ui.components.MonolithSlab
import com.monolith.app.ui.components.revealProgress
import com.monolith.app.ui.theme.MonolithButtonShape
import com.monolith.app.ui.theme.MonolithMotion
import com.monolith.app.ui.theme.MonolithShapes
import com.monolith.app.ui.theme.Spacing

// 48dp keys and 44dp slots: the picker is tapped repeatedly under time pressure, so its targets
// carry the full minimum. Slots are read, not tapped, and only need to stay legible beside them.
private val SlotSize = 44.dp
private val KeySize = 48.dp
private val LegendSwatchSize = 14.dp
private const val SYMBOL_PICKER_COLUMNS = 4

/**
 * The puzzle. Everything here used to be drawn with [androidx.compose.ui.graphics.RectangleShape]
 * and three hard-coded greys, which made it the only surface in the app outside its own geometry
 * and left it rendering as dark tiles on a cream background in light theme. It now sits on
 * [MonolithShapes.extraSmall] and reads its colours from the scheme.
 *
 * What is kept: flat fills, no elevation, no borders, and silhouettes rather than hues carrying
 * the symbols. The puzzle is still the hardest-edged thing Monolith draws -- it is just inside
 * the same system as everything else now.
 */
@Composable
fun CodeBreakerBoard(
    codeBreaker: CodeBreaker,
    codeRestarted: Boolean,
    onSubmitGuess: (List<Int>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var current by remember { mutableStateOf<List<Int>>(emptyList()) }
    val slotCount = codeBreaker.secret.size
    val haptics = LocalHapticFeedback.current

    MonolithSlab(
        landed = true,
        modifier = modifier,
        // Sized to content: the board is taller than a fixed slab and the whole screen scrolls.
        plinthHeightFraction = null,
        plinth = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // On the content, not on the slab: with the system bars hidden the plinth
                    // has to keep bleeding to the physical top edge, while the title still has
                    // to clear the camera cutout. Padding the slab itself put the black strip
                    // back that going edge-to-edge was meant to remove.
                    .safeDrawingPadding()
                    .padding(Spacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.codebreaker_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(Spacing.sm))
                // Amber only in the moment the last board burned -- the one state on this screen
                // the solver has to notice, since every deduction they'd made no longer applies.
                // Announced as well as coloured: a restart the solver misses wastes the next guess.
                Text(
                    if (codeRestarted) {
                        stringResource(R.string.codebreaker_out_of_tries, codeBreaker.triesLeft)
                    } else {
                        stringResource(
                            R.string.codebreaker_tries_left,
                            codeBreaker.triesLeft,
                            CodeBreaker.MAX_GUESSES,
                        )
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (codeRestarted) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(Modifier.height(Spacing.md))
                CodeBreakerLegend()
                Spacer(Modifier.height(Spacing.xl))

                codeBreaker.guesses.forEachIndexed { rowIndex, guess ->
                    // Only the newest row animates: the ones above it were resolved on earlier
                    // submits and re-revealing them on every recomposition would be a light show.
                    GuessRow(guess = guess, animate = rowIndex == codeBreaker.guesses.lastIndex)
                    Spacer(Modifier.height(Spacing.sm))
                }
                if (codeBreaker.guesses.isNotEmpty()) Spacer(Modifier.height(Spacing.md))

                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    (0 until slotCount).forEach { index ->
                        GuessSlot(symbol = current.getOrNull(index))
                    }
                }
            }
        },
        ground = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MonolithSymbolPicker(
                    onSymbolPress = { symbol ->
                        if (current.size < slotCount) {
                            current = current + symbol
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    },
                )
                Spacer(Modifier.height(Spacing.lg))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    TextButton(onClick = { current = emptyList() }, enabled = current.isNotEmpty()) {
                        Text(stringResource(R.string.codebreaker_clear))
                    }
                    OutlinedButton(
                        shape = MonolithButtonShape,
                        onClick = {
                            onSubmitGuess(current)
                            current = emptyList()
                        },
                        enabled = current.size == slotCount,
                    ) {
                        Text(stringResource(R.string.codebreaker_submit))
                    }
                }
            }
        },
    )
}

/** What each tile colour in a past guess means -- a legend reads faster than a sentence. */
@Composable
private fun CodeBreakerLegend(modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        LegendItem(
            color = slotColor(SlotResult.EXACT),
            label = stringResource(R.string.codebreaker_legend_exact),
        )
        LegendItem(
            color = slotColor(SlotResult.PARTIAL),
            label = stringResource(R.string.codebreaker_legend_partial),
        )
        LegendItem(
            color = slotColor(SlotResult.MISS),
            label = stringResource(R.string.codebreaker_legend_miss),
        )
    }
}

@Composable
private fun LegendItem(color: Color, label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(LegendSwatchSize)
                .clip(MonolithShapes.extraSmall)
                .background(color),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GuessRow(guess: CodeBreaker.Guess, animate: Boolean, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        guess.values.forEachIndexed { index, symbol ->
            GuessSlot(
                symbol = symbol,
                result = guess.slotResults[index],
                // Left to right, one slot at a time. Resolving all six at once is technically
                // the same information and practically unreadable -- the eye has nowhere to start.
                revealDelayMillis = if (animate) index * MonolithMotion.SlotRevealStaggerMillis else 0,
            )
        }
    }
}

/**
 * Amber = right symbol, right slot. Muted = right symbol, wrong slot. Sunken = not in the code.
 * The state is also exposed to TalkBack, so the result never depends on telling three fills
 * apart by colour alone.
 */
@Composable
private fun GuessSlot(
    symbol: Int?,
    result: SlotResult? = null,
    revealDelayMillis: Int = 0,
    modifier: Modifier = Modifier,
) {
    // The reveal is a fade of the whole tile rather than a colour tween: a MISS resolves to the
    // same fill an unresolved slot already has, so animating colour would leave the misses in a
    // row silently missing from the cascade.
    val revealed = revealProgress(revealDelayMillis)

    val emptyLabel = stringResource(R.string.codebreaker_empty_slot)
    val symbolLabel = symbol?.let { symbolName(it) } ?: emptyLabel
    val state = result?.let { stringResource(slotStateRes(it)) }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(SlotSize)
            .graphicsLayer { alpha = revealed }
            .clip(MonolithShapes.extraSmall)
            .background(slotColor(result))
            .semantics {
                contentDescription = symbolLabel
                if (state != null) stateDescription = state
            },
    ) {
        if (symbol != null) {
            SymbolSwatch(
                symbol = symbol,
                tint = symbolInk(),
                modifier = Modifier.size(SlotSize * 0.6f),
            )
        }
    }
}

/** Fixed-grid, flat, monochrome-block symbol picker -- wraps once the symbol count outgrows a row. */
@Composable
private fun MonolithSymbolPicker(onSymbolPress: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        (0 until CodeBreakerGenerator.SYMBOL_COUNT).chunked(SYMBOL_PICKER_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                row.forEach { symbol ->
                    val label = symbolName(symbol)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(KeySize)
                            .clip(MonolithShapes.extraSmall)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable { onSymbolPress(symbol) }
                            .semantics { contentDescription = label },
                    ) {
                        SymbolSwatch(
                            symbol = symbol,
                            tint = symbolInk(),
                            modifier = Modifier.size(KeySize * 0.55f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun slotColor(result: SlotResult?): Color = when (result) {
    SlotResult.EXACT -> MaterialTheme.colorScheme.secondary
    SlotResult.PARTIAL -> MaterialTheme.colorScheme.onSurfaceVariant
    SlotResult.MISS, null -> MaterialTheme.colorScheme.surfaceContainerHighest
}

/**
 * The ink every symbol is drawn in, on every fill: [onSurface], so a shape is the same colour
 * whether it landed exact, landed in the wrong slot, is absent, or is still sitting in the
 * picker. A symbol is an identity, not a state -- the tile behind it is what carries the result,
 * and changing the ink too made the same shape look like two different shapes.
 *
 * This costs contrast on the two coloured fills: #F5F5F0 on the brand amber is 2.3:1 and on the
 * mid grey 1.9:1. Solid silhouettes tolerate more than text does, and the result is also exposed
 * to TalkBack as a stateDescription, so colour is not the only signal either way.
 */
@Composable
private fun symbolInk(): Color = MaterialTheme.colorScheme.onSurface

private fun slotStateRes(result: SlotResult): Int = when (result) {
    SlotResult.EXACT -> R.string.codebreaker_slot_state_exact
    SlotResult.PARTIAL -> R.string.codebreaker_slot_state_partial
    SlotResult.MISS -> R.string.codebreaker_slot_state_miss
}

@Composable
private fun symbolName(symbol: Int): String = stringResource(
    when (symbol) {
        0 -> R.string.codebreaker_symbol_0
        1 -> R.string.codebreaker_symbol_1
        2 -> R.string.codebreaker_symbol_2
        3 -> R.string.codebreaker_symbol_3
        4 -> R.string.codebreaker_symbol_4
        5 -> R.string.codebreaker_symbol_5
        6 -> R.string.codebreaker_symbol_6
        else -> R.string.codebreaker_symbol_7
    },
)

/**
 * One of [CodeBreakerGenerator.SYMBOL_COUNT] fill patterns of the same rectangle -- distinguished
 * by silhouette, not colour, so the puzzle stays inside Monolith's deliberately monochrome visual
 * language instead of borrowing coloured pegs from real Mastermind. Each pattern is also named
 * for TalkBack (see [symbolName]).
 */
@Composable
private fun SymbolSwatch(symbol: Int, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        when (symbol) {
            0 -> drawRect(color = tint)
            1 -> drawRect(color = tint, style = Stroke(width = size.minDimension * 0.16f))
            2 -> drawRect(
                color = tint,
                topLeft = Offset(0f, size.height / 2f),
                size = Size(size.width, size.height / 2f),
            )
            3 -> drawRect(color = tint, size = Size(size.width, size.height / 2f))
            4 -> drawRect(color = tint, size = Size(size.width / 2f, size.height))
            5 -> drawRect(
                color = tint,
                topLeft = Offset(size.width / 2f, 0f),
                size = Size(size.width / 2f, size.height),
            )
            6 -> {
                // Two opposite quadrants -- a diagonal split, distinct from the half-fills above.
                drawRect(color = tint, size = Size(size.width / 2f, size.height / 2f))
                drawRect(
                    color = tint,
                    topLeft = Offset(size.width / 2f, size.height / 2f),
                    size = Size(size.width / 2f, size.height / 2f),
                )
            }
            else -> {
                // Inset center square -- a "dot", distinct from the full/half/quadrant fills above.
                val inset = size.minDimension * 0.28f
                drawRect(
                    color = tint,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - inset * 2f, size.height - inset * 2f),
                )
            }
        }
    }
}
