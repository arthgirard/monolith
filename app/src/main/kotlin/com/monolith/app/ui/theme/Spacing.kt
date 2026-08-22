package com.monolith.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Spacing is built on 1:4:9 -- the proportions of the slab in 2001, the squares of the first three
 * integers. [xs], [lg] and [xxl] are those three ratios at a 4dp unit; [sm] and [xl] are the
 * intermediates that make the scale usable in practice.
 *
 * A plain object rather than a CompositionLocal: there is only one scale here and nothing needs to
 * override it per-subtree.
 */
object Spacing {
    val xs = 4.dp // 1
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp // 4
    val xl = 24.dp
    val xxl = 36.dp // 9
}

/** Material's disabled opacity. The only alpha constant worth keeping. */
const val DisabledAlpha = 0.38f
