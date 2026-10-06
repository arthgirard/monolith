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
import com.monolith.app.data.backup.BackupCrypto
import com.monolith.app.data.backup.TagCodeCodec
import com.monolith.app.domain.model.NfcDispatchTech
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

    override suspend fun provisionTag(tag: Tag, code: String?): NfcLinkResult = withContext(Dispatchers.IO) {
        val uid = bytesToHex(tag.id)
        if (uid.isBlank()) {
            return@withContext NfcLinkResult.Failure("Tag has no readable identifier.")
        }

        val uri = "$TAG_BASE_URL$uid"
        val codeRecord = code?.let(BackupCrypto::decodeCode)?.let { codeRecord(tag.id, it) }
        val written = runCatching { writeNdef(tag, uri, codeRecord) }.getOrDefault(NdefWrite.FAILED)

        val link = when (written) {
            // Nothing to register: the tag now carries a monolith:// URI, and the NDEF filter
            // that matches it cannot be triggered by anybody else's tag.
            NdefWrite.WITH_CODE -> NfcTagLink(uid = uid, mode = TagLinkMode.SMART_NDEF, ndefUri = uri, code = code)
            // Asked for a code and got only the URI: the tag is too small, and stays quiet about it.
            NdefWrite.URI_ONLY -> NfcTagLink(uid = uid, mode = TagLinkMode.SMART_NDEF, ndefUri = uri, codeFits = codeRecord == null)
            NdefWrite.FAILED -> NfcTagLink(
                uid = uid,
                mode = TagLinkMode.FALLBACK_UID,
                ndefUri = null,
                dispatchTech = dispatchTechFor(tag),
            )
        }
        NfcLinkResult.Success(link)
    }

    override fun identifyTag(tag: Tag): String = bytesToHex(tag.id)

    override fun dispatchTechFor(tag: Tag): String? =
        NfcDispatchTech.narrowest(tag.techList.toList())

    override fun readCode(tag: Tag): String? {
        val message = Ndef.get(tag)?.cachedNdefMessage ?: return null
        val record = message.records.firstOrNull {
            it.tnf == NdefRecord.TNF_EXTERNAL_TYPE && String(it.type, Charsets.US_ASCII) == CODE_RECORD_TYPE
        } ?: return null
        return TagCodeCodec.decode(tag.id, record.payload)?.let(BackupCrypto::encodeCode)
    }

    override fun existingLink(tag: Tag, code: String): NfcTagLink {
        val uid = bytesToHex(tag.id)
        return NfcTagLink(uid = uid, mode = TagLinkMode.SMART_NDEF, ndefUri = "$TAG_BASE_URL$uid", code = code)
    }

    private enum class NdefWrite { WITH_CODE, URI_ONLY, FAILED }

    private fun codeRecord(uid: ByteArray, master: ByteArray): NdefRecord =
        NdefRecord.createExternal(CODE_DOMAIN, CODE_TYPE, TagCodeCodec.encode(uid, master))

    /** The URI always comes first: Android dispatches a background tap on the first record only. */
    private fun writeNdef(tag: Tag, uri: String, codeRecord: NdefRecord?): NdefWrite {
        val uriRecord = NdefRecord.createUri(uri)
        val uriOnly = NdefMessage(arrayOf(uriRecord))
        val withCode = codeRecord?.let { NdefMessage(arrayOf(uriRecord, it)) }

        Ndef.get(tag)?.let { ndef ->
            return try {
                ndef.connect()
                when {
                    !ndef.isWritable -> NdefWrite.FAILED
                    withCode != null && withCode.byteArrayLength <= ndef.maxSize -> {
                        ndef.writeNdefMessage(withCode)
                        NdefWrite.WITH_CODE
                    }
                    uriOnly.byteArrayLength <= ndef.maxSize -> {
                        ndef.writeNdefMessage(uriOnly)
                        NdefWrite.URI_ONLY
                    }
                    else -> NdefWrite.FAILED
                }
            } catch (_: Exception) {
                NdefWrite.FAILED
            } finally {
                runCatching { ndef.close() }
            }
        }

        NdefFormatable.get(tag)?.let { formatable ->
            // A blank tag says nothing about its size until formatted, so try both records first
            // and fall back to the URI alone.
            if (withCode != null && format(formatable, withCode)) return NdefWrite.WITH_CODE
            return if (format(formatable, uriOnly)) NdefWrite.URI_ONLY else NdefWrite.FAILED
        }

        return NdefWrite.FAILED
    }

    private fun format(formatable: NdefFormatable, message: NdefMessage): Boolean = try {
        formatable.connect()
        formatable.format(message)
        true
    } catch (_: Exception) {
        false
    } finally {
        runCatching { formatable.close() }
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
        private const val CODE_DOMAIN = "monolith.app"
        private const val CODE_TYPE = "k"

        /** How Android spells an external record's type: lower-case "domain:type". */
        private const val CODE_RECORD_TYPE = "$CODE_DOMAIN:$CODE_TYPE"
    }
}
