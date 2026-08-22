package com.monolith.app.nfc

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.monolith.app.domain.model.NfcDispatchTech
import com.monolith.app.domain.model.NfcTagLink
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides how much of the phone's NFC traffic Monolith is allowed to see.
 *
 * Monolith recognises tags it could not write by their hardware UID, and the only way to see one
 * arrive while the app is in the background is a TECH_DISCOVERED filter in the manifest. That
 * filter used to sit on the tap overlay and list every technology Android knows, which made
 * Monolith the background handler for nearly every tag the phone met: bank cards, transit passes,
 * hotel keys, other apps' tags. Each of those taps was swallowed, and the app that wanted it
 * never got it.
 *
 * The filters are now one disabled `activity-alias` per technology, and this turns on exactly the
 * one the linked tag needs:
 *
 *  - no tag linked, or an NDEF-linked tag: nothing is enabled at all, and no tag but Monolith's
 *    own (which carries a `monolith://` URI) can reach the app;
 *  - a UID-linked tag: only that tag's narrowest technology, so listening for a Skylander does
 *    not also mean catching every contactless card in your wallet.
 *
 * Component enabled state is persistent, so this survives reboots on its own. It is re-applied on
 * every app start anyway, to recover from a restore onto a fresh install where the tag is in
 * DataStore but the aliases are back at their manifest defaults.
 */
@Singleton
class NfcDispatchGate @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun apply(link: NfcTagLink?) {
        val wanted = NfcDispatchTech.forLink(link)
        DISPATCH_TECHS.forEach { tech ->
            setAliasEnabled(tech, enabled = tech == wanted)
        }
    }

    private fun setAliasEnabled(tech: String, enabled: Boolean) {
        val component = ComponentName(context, "$ALIAS_PREFIX$tech")
        val target = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        // Guarded: setComponentEnabledSetting throws if the component is unknown, which would
        // take the whole app down at startup over a filter that only narrows behaviour.
        runCatching {
            if (context.packageManager.getComponentEnabledSetting(component) == target) return
            context.packageManager.setComponentEnabledSetting(
                component,
                target,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    private companion object {
        const val ALIAS_PREFIX = "com.monolith.app.nfc.TagDispatch"

        /** Must match the activity-alias names in the manifest. */
        val DISPATCH_TECHS = NfcDispatchTech.PRIORITY
    }
}
