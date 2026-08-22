package com.monolith.app.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.monolith.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A pre-inflated, always-ready full-screen overlay that [show] can paint over the display with
 * no Activity launch in the critical path. [AppBlockAccessibilityService] calls [show] the
 * instant it detects a blocked foreground app, before starting [com.monolith.app.ui.bypass.BlockOverlayActivity],
 * so there's no window where the blocked app's already-drawn frame is left visible while that
 * Activity cold-starts its Hilt graph and Compose tree. The Activity calls [hide] once its own
 * content has taken over the screen.
 *
 * Must be called from the main thread: both call sites (an accessibility event callback and
 * `Activity.onResume`) already run there, and doing the WindowManager work synchronously lets
 * [show] report whether the cover actually painted, instead of only firing-and-forgetting.
 *
 * Requires the "display over other apps" permission, already required during onboarding; if it's
 * missing or revoked, [show] returns false and does nothing, so callers can fall back to another
 * mitigation instead.
 */
@Singleton
class BlockOverlayGuard @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var coverView: View? = null

    private val failSafeHide = Runnable { hide() }

    /** Returns true if the cover is now painted (or already was). */
    fun show(): Boolean {
        if (coverView != null) return true
        if (!Settings.canDrawOverlays(context)) return false

        // Resolved per show() rather than cached: this is a @Singleton with an application
        // Context, so a value captured once would keep painting the old ramp after the user
        // flips light/dark. Hard black here used to flash against a light theme's #F5F5F0.
        val coverColor = ContextCompat.getColor(context, R.color.monolith_background)
        val view = View(context).apply { setBackgroundColor(coverColor) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.OPAQUE,
        )
        return runCatching { windowManager.addView(view, params) }
            .onSuccess {
                coverView = view
                // If BlockOverlayActivity never calls hide() (e.g. it crashes on launch), this
                // failsafe keeps the cover from becoming a permanent black screen.
                mainHandler.postDelayed(failSafeHide, FAILSAFE_TIMEOUT_MS)
            }
            .isSuccess
    }

    fun hide() {
        mainHandler.removeCallbacks(failSafeHide)
        coverView?.let { view -> runCatching { windowManager.removeView(view) } }
        coverView = null
    }

    private companion object {
        const val FAILSAFE_TIMEOUT_MS = 5_000L
    }
}
