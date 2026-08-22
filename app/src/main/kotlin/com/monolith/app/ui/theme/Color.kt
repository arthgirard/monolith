package com.monolith.app.ui.theme

import androidx.compose.ui.graphics.Color

// Monolith is deliberately near-monochrome; the accent only shows up where a decision is being
// made. The ramps below are the palette the app originally shipped, with the gaps filled in: the
// old light scheme collapsed surface, surfaceVariant and outline onto a single value, so cards,
// borders and chart tracks were indistinguishable from each other.

// --- Light ------------------------------------------------------------------------------------
// Faintly warm rather than a pure grey. Cards go to true white, which is what gives the ramp the
// separation it was missing without introducing a second hue.
val MonolithWhite = Color(0xFFF5F5F0)
val MonolithCard = Color(0xFFFFFFFF)
val MonolithOffWhite = Color(0xFFE8E8E2)
// Borders here are quiet on purpose: a control is identified by its label, not by its outline, so
// the line only needs to suggest an edge. MonolithDivider is quieter still, for splitting rows
// that already sit inside a shared container.
val MonolithDivider = Color(0xFFEFEFEA)
val MonolithBlack = Color(0xFF0B0B0D)
val MonolithGrayLight = Color(0xFF65656A)

// --- Dark -------------------------------------------------------------------------------------
val MonolithNearBlack = Color(0xFF17171A)
val MonolithSunken = Color(0xFF212125)
val MonolithGrayDark = Color(0xFF2C2C30)
val MonolithGray = Color(0xFF8A8A8F)

// --- Accent -----------------------------------------------------------------------------------
// One accent, spent once per screen at most. MonolithAmber is the brand value and clears AA on
// the dark ramp; on the light ramp it manages only ~3.3:1, so anything carrying text or an icon
// there uses AmberDeep. Both read as the same amber.
val MonolithAmber = Color(0xFFD97706)
val AmberDeep = Color(0xFFA85906)
val AmberWashLight = Color(0xFFF4E7D5)
val AmberWashDark = Color(0xFF33250F)

val MonolithRed = Color(0xFFB3261E)
val RedSoft = Color(0xFFE8908A)
val RedWashLight = Color(0xFFF6DEDC)
val RedWashDark = Color(0xFF331A18)
