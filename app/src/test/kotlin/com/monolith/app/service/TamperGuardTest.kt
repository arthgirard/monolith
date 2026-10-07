package com.monolith.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TamperGuardTest {

    private val settings = "com.android.settings"
    private val installer = "com.google.android.packageinstaller"
    private val permissions = "com.google.android.permissioncontroller"
    private val systemUi = "com.android.systemui"

    private fun guarded(
        packageName: String,
        vararg nodes: GuardNode,
        windowTitle: String? = null,
        guardUninstall: Boolean = true,
    ) = TamperGuard.isGuardedScreen(packageName, "Monolith", windowTitle, nodes.asSequence(), guardUninstall)

    @Test
    fun `the uninstall prompt for monolith is guarded`() {
        assertTrue(guarded(installer, GuardNode(text = "Monolith"), GuardNode(text = "Do you want to uninstall this app?")))
    }

    @Test
    fun `the uninstall prompt is let through when the uninstall guard is off`() {
        assertFalse(guarded(installer, GuardNode(text = "Monolith"), guardUninstall = false))
    }

    @Test
    fun `monolith's app info stays shut even with the uninstall guard off`() {
        assertTrue(
            guarded(
                settings,
                GuardNode(text = "Monolith", viewId = "com.android.settings:id/entity_header_title"),
                guardUninstall = false,
            ),
        )
    }

    @Test
    fun `monolith's permissions page is guarded`() {
        assertTrue(guarded(permissions, GuardNode(text = "Monolith", viewId = "$permissions:id/entity_header_title")))
    }

    @Test
    fun `monolith's row in the active apps panel is guarded`() {
        assertTrue(guarded(systemUi, GuardNode(text = "Monolith", viewId = "$systemUi:id/fgs_manager_app_item_label")))
    }

    @Test
    fun `the shade naming monolith in a notification is not`() {
        assertFalse(
            guarded(
                systemUi,
                GuardNode(text = "Monolith", viewId = "android:id/app_name_text"),
                GuardNode(text = "Use Monolith", isCheckable = false),
                windowTitle = "Monolith",
            ),
        )
    }

    @Test
    fun `the uninstall prompt for another app is not`() {
        assertFalse(guarded(installer, GuardNode(text = "Chrome"), GuardNode(text = "Do you want to uninstall this app?")))
    }

    @Test
    fun `monolith's app info header is guarded`() {
        assertTrue(guarded(settings, GuardNode(text = "Monolith", viewId = "com.android.settings:id/entity_header_title")))
    }

    // Pixel 10, Android 17: App info is Compose (SpaActivity), so no node carries a view ID. The
    // header is a bare "Monolith" text outside anything clickable; the all-apps list puts the same
    // text inside a clickable row.
    @Test
    fun `a compose app info header with no view id is guarded`() {
        assertTrue(guarded(settings, GuardNode(text = "Monolith"), GuardNode(text = "Forcer l'arrêt", insideClickable = true)))
    }

    @Test
    fun `a compose app list row naming monolith is not`() {
        assertFalse(
            guarded(
                settings,
                GuardNode(text = "Toutes les applis"),
                GuardNode(text = "Monolith", insideClickable = true),
                GuardNode(text = "7,17 Mo", insideClickable = true),
            ),
        )
    }

    // The storage page's header links back to App info, so its title sits inside a clickable;
    // the view ID is what identifies it there.
    @Test
    fun `a clickable entity header titled monolith is guarded`() {
        assertTrue(
            guarded(
                settings,
                GuardNode(text = "Monolith", viewId = "com.android.settings:id/entity_header_title", insideClickable = true),
            ),
        )
    }

    @Test
    fun `a collapsing toolbar titled monolith is guarded`() {
        assertTrue(guarded(settings, GuardNode(contentDescription = "Monolith", viewId = "com.android.settings:id/collapsing_toolbar")))
    }

    @Test
    fun `a window titled monolith is guarded`() {
        assertTrue(guarded(settings, windowTitle = "Monolith"))
    }

    @Test
    fun `the accessibility switch for monolith is guarded`() {
        assertTrue(guarded(settings, GuardNode(text = "Use Monolith", isCheckable = true)))
    }

    @Test
    fun `the aosp switch bar label for monolith is guarded`() {
        assertTrue(guarded(settings, GuardNode(text = "Use Monolith", viewId = "com.android.settings:id/switch_text")))
    }

    @Test
    fun `a list that merely contains monolith as a row is not`() {
        assertFalse(
            guarded(
                settings,
                GuardNode(contentDescription = "Apps", viewId = "com.android.settings:id/collapsing_toolbar"),
                GuardNode(text = "Monolith", viewId = "android:id/title", insideClickable = true),
                GuardNode(text = "Chrome", viewId = "android:id/title", insideClickable = true),
                GuardNode(isCheckable = true),
                windowTitle = "Apps",
            ),
        )
    }

    @Test
    fun `another app's info page is not`() {
        assertFalse(guarded(settings, GuardNode(text = "Chrome", viewId = "com.android.settings:id/entity_header_title")))
    }

    @Test
    fun `other packages are never guarded`() {
        assertFalse(guarded("com.example.launcher", GuardNode(text = "Monolith", viewId = "x:id/entity_header_title")))
    }

    @Test
    fun `only settings, permissions, the installer and the shade are watched`() {
        assertTrue(TamperGuard.watches(settings))
        assertTrue(TamperGuard.watches(installer))
        assertTrue(TamperGuard.watches(permissions))
        assertTrue(TamperGuard.watches(systemUi))
        assertFalse(TamperGuard.watches("com.android.chrome"))
    }
}
