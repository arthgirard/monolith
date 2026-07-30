package com.monolith.app.nfc

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import com.monolith.app.domain.model.NfcLinkResult
import com.monolith.app.domain.model.NfcTagLink
import com.monolith.app.domain.model.TagLinkMode
import com.monolith.app.domain.repository.TagProvisioner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns all NFC hardware interaction: foreground dispatch setup, writing the smart-provisioning
 * NDEF URI, and the read-only UID fallback. Tag *identity* is always the hardware UID; the NDEF
 * write is a convenience so a background tap routes straight to [com.monolith.app.ui.tapoverlay.NfcTapOverlayActivity]
 * via a custom URI scheme rather than https: Android 12+ only direct-launches an app for an
 * http(s) link once the domain passes Digital Asset Links verification, which an unhosted domain
 * can never do; the tap would silently fall back to "open in browser" and fail. A custom scheme
 * has no such verification step, at the cost of an uninstalled phone getting "no app found"
 * instead of a download page.
 */
@Singleton
class NfcManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : TagProvisioner {

    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(context)

    val isNfcSupported: Boolean get() = adapter != null
    val isNfcEnabled: Boolean get() = adapter?.isEnabled == true

    override suspend fun provisionTag(tag: Tag): NfcLinkResult = withContext(Dispatchers.IO) {
        val uid = bytesToHex(tag.id)
        if (uid.isBlank()) {
            return@withContext NfcLinkResult.Failure("Tag has no readable identifier.")
        }

        val uri = "$TAG_BASE_URL$uid"
        val wroteNdef = runCatching { writeNdefUri(tag, uri) }.getOrDefault(false)

        val link = if (wroteNdef) {
            NfcTagLink(uid = uid, mode = TagLinkMode.SMART_NDEF, ndefUri = uri)
        } else {
            NfcTagLink(uid = uid, mode = TagLinkMode.FALLBACK_UID, ndefUri = null)
        }
        NfcLinkResult.Success(link)
    }

    override fun identifyTag(tag: Tag): String = bytesToHex(tag.id)

    private fun writeNdefUri(tag: Tag, uri: String): Boolean {
        val message = NdefMessage(arrayOf(NdefRecord.createUri(uri)))

        Ndef.get(tag)?.let { ndef ->
            return try {
                ndef.connect()
                if (!ndef.isWritable || ndef.maxSize < message.toByteArray().size) {
                    false
                } else {
                    ndef.writeNdefMessage(message)
                    true
                }
            } catch (_: Exception) {
                false
            } finally {
                runCatching { ndef.close() }
            }
        }

        NdefFormatable.get(tag)?.let { formatable ->
            return try {
                formatable.connect()
                formatable.format(message)
                true
            } catch (_: Exception) {
                false
            } finally {
                runCatching { formatable.close() }
            }
        }

        return false
    }

    fun enableForegroundDispatch(activity: Activity) {
        val adapter = adapter ?: return
        val intent = Intent(context, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val filters = arrayOf(
            IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED),
            IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED),
            IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED),
        )
        adapter.enableForegroundDispatch(activity, pendingIntent, filters, null)
    }

    fun disableForegroundDispatch(activity: Activity) {
        adapter?.disableForegroundDispatch(activity)
    }

    fun extractTagFromIntent(intent: Intent): Tag? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString(separator = "") { "%02X".format(it) }

    companion object {
        const val TAG_BASE_URL = "monolith://tag/"
    }
}
