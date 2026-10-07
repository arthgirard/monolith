package com.monolith.app.service

import com.monolith.app.domain.model.SystemPackages

/** What [TamperGuard] needs to know about one node on screen, kept free of Android types. */
data class GuardNode(
    val text: String? = null,
    val contentDescription: String? = null,
    val viewId: String? = null,
    val isCheckable: Boolean = false,
    /** Whether this node or any node above it is clickable, as a row in a list is. */
    val insideClickable: Boolean = false,
)

/**
 * Recognises the screens that would let someone take an active Monolith apart.
 *
 * Always, while Monolith is on: the Settings and permission pages that are about Monolith itself
 * (App info with its Force stop and Clear storage, the accessibility page, its permissions), and
 * its row in the shade's Active apps panel. Force stopping is the worst of these: Android drops
 * the accessibility service from the enabled list, so blocking stops until it is granted again.
 *
 * Only with the uninstall guard on: the system uninstall prompt. That one is the user's choice,
 * since uninstalling ends Monolith openly rather than switching it off behind its back.
 *
 * Settings is read by structure, not by its wording, which changes with every language: a page
 * counts when its title is Monolith's name. Lists that merely contain Monolith as a row (all
 * apps, accessibility services, battery usage) don't, so Settings stays usable for anything else.
 *
 * A title is Monolith's name either in one of Settings' title views, or anywhere outside a
 * clickable. The second is what catches Compose pages, which carry no view IDs at all: App info
 * is one since Android 14 (Pixel 10, Android 17 confirmed), its header a bare text where the app
 * list puts the same name inside a clickable row. The first is still needed where a header is
 * itself clickable, as the storage page's links back to App info.
 *
 * The view IDs are AOSP's. OEM skins may name theirs differently; the service logs what each
 * Settings window shows so a miss can be identified on a real device and added here.
 */
object TamperGuard {

    /** The system uninstall prompt across AOSP, Google and Samsung builds. */
    private val INSTALLER_PACKAGES = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.samsung.android.packageinstaller",
    )

    /** Settings and the permission controller, whose app pages share Settings' layouts. */
    private val SETTINGS_PACKAGES = setOf(
        SystemPackages.SETTINGS,
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
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

    /** An app's name in the Active apps panel (Android 13+), next to its Stop button. */
    private const val ACTIVE_APP_LABEL_SUFFIX = ":id/fgs_manager_app_item_label"

    fun watches(packageName: String): Boolean =
        packageName in SETTINGS_PACKAGES || packageName in INSTALLER_PACKAGES || packageName == SystemPackages.SYSTEM_UI

    /**
     * Whether the window from [packageName], titled [windowTitle] and showing [nodes], is one of
     * the guarded screens for the app called [appLabel]. The uninstall prompt counts only when
     * [guardUninstall] is set. [nodes] is consumed lazily and stops at the first match.
     */
    fun isGuardedScreen(
        packageName: String,
        appLabel: String,
        windowTitle: String?,
        nodes: Sequence<GuardNode>,
        guardUninstall: Boolean,
    ): Boolean {
        if (appLabel.isBlank()) return false
        return when (packageName) {
            // The prompt only ever names the one app it would remove.
            in INSTALLER_PACKAGES -> guardUninstall && nodes.any { it.mentions(appLabel) }
            in SETTINGS_PACKAGES -> windowTitle.isLabel(appLabel) || nodes.any { it.isPageAbout(appLabel) }
            // Matched by the panel's own row ID: the shade around it names Monolith too, in its
            // notifications, and those must stay reachable.
            SystemPackages.SYSTEM_UI -> nodes.any {
                it.viewId?.endsWith(ACTIVE_APP_LABEL_SUFFIX) == true && it.text.isLabel(appLabel)
            }
            else -> false
        }
    }

    private fun GuardNode.isPageAbout(appLabel: String): Boolean {
        val titled = (text.isLabel(appLabel) || contentDescription.isLabel(appLabel)) &&
            (!insideClickable || TITLE_VIEW_ID_SUFFIXES.any { viewId?.endsWith(it) == true })
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
