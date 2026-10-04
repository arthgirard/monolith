package com.monolith.app.ui.friends

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.monolith.app.R
import kotlinx.coroutines.delay

private const val COPIED_FOR_MILLIS = 2_000L

/** Hands the invite to the system share sheet, as a line a friend can read on its own. */
fun shareInvite(context: Context, inviteCode: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, context.getString(R.string.friends_invite_message, inviteCode))
    }
    context.startActivity(Intent.createChooser(send, context.getString(R.string.friends_invite_share)))
}

/**
 * Copies to the clipboard and reports [copied] for a moment after. Older Android shows nothing
 * on a copy, and a bottom sheet would cover a snackbar, so the button itself confirms it.
 */
class InviteCopier internal constructor(private val write: (String) -> Unit) {
    var copied by mutableStateOf(false)
        internal set

    /** Bumped on every copy, so a second tap restarts the moment instead of riding the first. */
    internal var stamp by mutableIntStateOf(0)
        private set

    fun copy(text: String) {
        write(text)
        copied = true
        stamp++
    }
}

@Composable
fun rememberInviteCopier(): InviteCopier {
    val clipboard = LocalClipboardManager.current
    val copier = remember(clipboard) { InviteCopier { clipboard.setText(AnnotatedString(it)) } }
    LaunchedEffect(copier, copier.stamp) {
        if (!copier.copied) return@LaunchedEffect
        delay(COPIED_FOR_MILLIS)
        copier.copied = false
    }
    return copier
}
