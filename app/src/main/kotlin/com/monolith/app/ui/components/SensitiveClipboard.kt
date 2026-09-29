package com.monolith.app.ui.components

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/**
 * Copies [text] marked sensitive, so Android 13+ hides it from the clipboard preview and keyboards
 * don't offer it as a suggestion. Used for the recovery code, which is the account.
 */
fun copySensitive(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, text)
    val sensitiveKey = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ClipDescription.EXTRA_IS_SENSITIVE
    } else {
        "android.content.extra.IS_SENSITIVE"
    }
    clip.description.extras = PersistableBundle().apply { putBoolean(sensitiveKey, true) }
    clipboard.setPrimaryClip(clip)
}
