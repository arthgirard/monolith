package com.monolith.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Small, consistent radii: enough to stop an edge looking accidental, not enough to look soft.
// Radii live here and nowhere else -- reach for MaterialTheme.shapes rather than a literal dp.
//
//   extraSmall  chips, badges, swatches
//   small       inline tiles (app icons), compact buttons
//   medium      rows, secondary cards
//   large       primary cards, dialogs
//   extraLarge  full-bleed panels, the widget
val MonolithShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/**
 * Buttons carry their own shape because Material routes them through a CornerFull token that
 * resolves straight to CircleShape -- [MonolithShapes] never gets a say, so a full pill is the
 * default no matter what the scale says. Squaring them off a little lines the buttons up with the
 * cards and rows around them.
 */
val MonolithButtonShape = RoundedCornerShape(12.dp)
