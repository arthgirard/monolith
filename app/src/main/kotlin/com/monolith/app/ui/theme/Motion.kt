package com.monolith.app.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * A heavy thing coming to rest: fast to start, long to settle, and no overshoot at the end.
 * Monolith's overlays are meant to read as mass arriving, not as UI springing into place, so
 * nothing in the app uses a spring and nothing bounces past its target.
 */
val MonolithSettle = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * One vocabulary for overlay motion. Before this the app had a [tween] of 260ms in the tap
 * overlay and one of 300ms in the nav host, chosen independently; anything new would have been
 * a third unrelated number.
 */
object MonolithMotion {
    /** The plinth's fall. Long enough to read as weight, short enough not to delay the wall. */
    const val SlabDropMillis = 420

    /** Every fade of content onto an already-settled surface. */
    const val ContentFadeMillis = 200

    /** Gap between consecutive staggered elements. */
    const val StaggerMillis = 80

    /** The plinth's retract on a granted unlock, run while the app launches behind it. */
    const val SlabLiftMillis = 220

    /** State-to-state within an overlay that is already on screen (e.g. app-to-app re-target). */
    const val CrossfadeMillis = 180

    /**
     * The slab's fall on a flash -- a tag tap's confirmation, not a wall being imposed. Shorter
     * than [SlabDropMillis] because the whole surface only lives for [FlashHoldMillis]: at the
     * wall's pace the mass would still be settling when it was time to leave.
     */
    const val FlashDropMillis = 260

    /** Stagger between a flash's elements. Tighter than [StaggerMillis] for the same reason. */
    const val FlashStaggerMillis = 60

    /**
     * How long a flash stays up before handing the screen back. Longer than the 1200ms these
     * used to hold: the surface now has something to read on it rather than one icon and a
     * label, and the drop costs the first quarter-second of that.
     */
    const val FlashHoldMillis = 1500L

    /** Gap between consecutive slots resolving along a submitted guess row. */
    const val SlotRevealStaggerMillis = 60

    /** One slot's colour resolving. */
    const val SlotRevealMillis = 160
}

/**
 * False when the user has turned animations off system-wide (Developer options -> "Remove
 * animations", or an accessibility preference that zeroes the same setting). Callers snap
 * straight to the end state rather than shortening the animation: a 40ms slab drop is worse
 * than no slab drop.
 *
 * Read once per composition rather than observed -- changing this setting restarts the
 * animating process, so there is no live value to miss.
 */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    }
}
