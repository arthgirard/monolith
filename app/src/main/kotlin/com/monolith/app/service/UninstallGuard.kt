package com.monolith.app.service

import com.monolith.app.domain.model.SystemPackages

/** What [UninstallGuard] needs to know about one node on screen, kept free of Android types. */
data class GuardNode(
    val text: String? = null,
    val contentDescription: String? = null,
    val viewId: String? = null,
    val isCheckable: Boolean = false,
)

/**
 * Recognises the screens that would let someone take an active Monolith apart: the system
 * uninstall prompt, and the Settings pages that are about Monolith itself (App info with its
 * Uninstall, Force stop and Clear storage, and the accessibility page that switches the blocker
 * off). Turning off the accessibility service is the bigger escape of the two, so guarding the
 * uninstall alone would be for show.
 *
 * Settings is read by structure, not by its wording, which changes with every language: a page
 * counts when its title is Monolith's name. Lists that merely contain Monolith as a row (all
 * apps, accessibility services, battery usage) don't, so Settings stays usable for anything else.
 *
 * The view IDs are AOSP's. OEM skins may name theirs differently; the service logs what each
 * Settings window shows so a miss can be identified on a real device and added here.
 */
object UninstallGuard {

    /** The system uninstall prompt across AOSP, Google and Samsung builds. */
    private val INSTALLER_PACKAGES = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.samsung.android.packageinstaller",
    )

    /**
     * Where Settings puts a page's title: App info's header, the collapsing toolbar (whose
     * content description carries the title) and the classic action bar.
     */
    private val TITLE_VIEW_ID_SUFFIXES = listOf(
        ":id/entity_header_title",
        ":id/collapsing_toolbar",
        ":id/action_bar",
        ":id/toolbar",
    )

    fun watches(packageName: String): Boolean =
        packageName == SystemPackages.SETTINGS || packageName in INSTALLER_PACKAGES

    /**
     * Whether the window from [packageName], titled [windowTitle] and showing [nodes], is one of
     * the guarded screens for the app called [appLabel]. [nodes] is consumed lazily and stops at
     * the first match.
     */
    fun isGuardedScreen(
        packageName: String,
        appLabel: String,
        windowTitle: String?,
        nodes: Sequence<GuardNode>,
    ): Boolean {
        if (appLabel.isBlank()) return false
        return when {
            // The prompt only ever names the one app it would remove.
            packageName in INSTALLER_PACKAGES -> nodes.any { it.mentions(appLabel) }
            packageName == SystemPackages.SETTINGS ->
                windowTitle.isLabel(appLabel) || nodes.any { it.isPageAbout(appLabel) }
            else -> false
        }
    }

    private fun GuardNode.isPageAbout(appLabel: String): Boolean {
        val titled = (text.isLabel(appLabel) || contentDescription.isLabel(appLabel)) &&
            TITLE_VIEW_ID_SUFFIXES.any { viewId?.endsWith(it) == true }
        // The accessibility page's main switch reads "Use Monolith", on the switch itself or on
        // the AOSP switch bar's label beside it; a list row's switch carries no text of its own.
        val switchFor = (isCheckable || viewId?.endsWith(":id/switch_text") == true) && mentions(appLabel)
        return titled || switchFor
    }

    private fun GuardNode.mentions(appLabel: String): Boolean =
        text?.contains(appLabel, ignoreCase = true) == true ||
            contentDescription?.contains(appLabel, ignoreCase = true) == true

    private fun String?.isLabel(appLabel: String): Boolean = this?.trim().equals(appLabel, ignoreCase = true)
}
