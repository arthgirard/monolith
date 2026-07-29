package com.monolith.app.nfc

import android.nfc.Tag
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class NfcBusMode { TOGGLE, LINKING }

/**
 * Fans a tapped [Tag] out from [com.monolith.app.ui.MainActivity]'s intent handling to whichever
 * screen should react. [mode] gates who's allowed to act: the NFC-link screen flips it to
 * LINKING while it's waiting for a tap so a tag write-in-progress can't also be misread as a
 * toggle attempt on the Home screen.
 */
@Singleton
class NfcTagBus @Inject constructor() {
    private val _tagEvents = MutableSharedFlow<Tag>(extraBufferCapacity = 1)
    val tagEvents: SharedFlow<Tag> = _tagEvents.asSharedFlow()

    private val _mode = MutableStateFlow(NfcBusMode.TOGGLE)
    val mode: StateFlow<NfcBusMode> = _mode.asStateFlow()

    fun setMode(mode: NfcBusMode) {
        _mode.value = mode
    }

    fun emit(tag: Tag) {
        _tagEvents.tryEmit(tag)
    }
}
