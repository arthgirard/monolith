package com.monolith.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UninstallGuardTest {

    private val settings = "com.android.settings"
    private val installer = "com.google.android.packageinstaller"

    private fun guarded(packageName: String, vararg nodes: GuardNode, windowTitle: String? = null) =
        UninstallGuard.isGuardedScreen(packageName, "Monolith", windowTitle, nodes.asSequence())

    @Test
    fun `the uninstall prompt for monolith is guarded`() {
        assertTrue(guarded(installer, GuardNode(text = "Monolith"), GuardNode(text = "Do you want to uninstall this app?")))
    }

    @Test
    fun `the uninstall prompt for another app is not`() {
        assertFalse(guarded(installer, GuardNode(text = "Chrome"), GuardNode(text = "Do you want to uninstall this app?")))
    }

    @Test
    fun `monolith's app info header is guarded`() {
        assertTrue(guarded(settings, GuardNode(text = "Monolith", viewId = "com.android.settings:id/entity_header_title")))
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
                GuardNode(text = "Monolith", viewId = "android:id/title"),
                GuardNode(text = "Chrome", viewId = "android:id/title"),
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
    fun `only settings and the installer are watched`() {
        assertTrue(UninstallGuard.watches(settings))
        assertTrue(UninstallGuard.watches(installer))
        assertFalse(UninstallGuard.watches("com.android.chrome"))
    }
}
