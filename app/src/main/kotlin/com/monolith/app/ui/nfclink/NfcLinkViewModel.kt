package com.monolith.app.ui.nfclink

import android.nfc.Tag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monolith.app.domain.model.NfcLinkResult
import com.monolith.app.domain.model.TagLinkMode
import com.monolith.app.domain.usecase.LinkNfcTagUseCase
import com.monolith.app.domain.usecase.TagCheck
import com.monolith.app.domain.usecase.TagRestoreResult
import com.monolith.app.domain.usecase.TagRestoreUseCase
import com.monolith.app.nfc.NfcBusMode
import com.monolith.app.nfc.NfcManager
import com.monolith.app.nfc.NfcTagBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface NfcLinkStatus {
    data object NfcUnsupported : NfcLinkStatus
    data object WaitingForTap : NfcLinkStatus
    data object Writing : NfcLinkStatus
    data class Success(val mode: TagLinkMode, val carriesCode: Boolean) : NfcLinkStatus
    data class Error(val message: String) : NfcLinkStatus

    /** Reached by tapping a tag on this screen while Monolith is active. */
    data object Locked : NfcLinkStatus

    // Setup only: the tapped tag carries a code from an earlier install.
    data object Checking : NfcLinkStatus
    data class OfferRestore(val backupAt: Long?, val hasBackup: Boolean) : NfcLinkStatus
    data object CheckFailed : NfcLinkStatus
    data object Unreadable : NfcLinkStatus
    data object Restoring : NfcLinkStatus
    data class Restored(val hasBackup: Boolean) : NfcLinkStatus

    /** Start fresh was chosen. The tag has usually left the phone by then, so it has to come back. */
    data object TapAgain : NfcLinkStatus
}

@HiltViewModel
class NfcLinkViewModel @Inject constructor(
    private val linkNfcTag: LinkNfcTagUseCase,
    private val tagRestore: TagRestoreUseCase,
    private val nfcTagBus: NfcTagBus,
    nfcManager: NfcManager,
) : ViewModel() {

    private val _status = MutableStateFlow<NfcLinkStatus>(
        if (nfcManager.isNfcSupported) NfcLinkStatus.WaitingForTap else NfcLinkStatus.NfcUnsupported,
    )
    val status: StateFlow<NfcLinkStatus> = _status

    /** Set by the setup step: only there can a tapped tag bring an earlier install back. */
    var offersRestore = false

    init {
        nfcTagBus.setMode(NfcBusMode.LINKING)
        nfcTagBus.tagEvents.onEach(::onTag).launchIn(viewModelScope)
    }

    private suspend fun onTag(tag: Tag) {
        if (_status.value in BUSY || _status.value is NfcLinkStatus.Restored) return
        if (offersRestore && _status.value != NfcLinkStatus.TapAgain) {
            _status.value = NfcLinkStatus.Checking
            if (show(tagRestore.check(tag))) return
        }
        link(tag)
    }

    /** True when [check] needs an answer before anything is written to the tag. */
    private fun show(check: TagCheck): Boolean {
        _status.value = when (check) {
            TagCheck.NoCode -> return false
            is TagCheck.Offer -> NfcLinkStatus.OfferRestore(check.backupAt, check.hasBackup)
            TagCheck.Unreachable -> NfcLinkStatus.CheckFailed
            TagCheck.Unreadable -> NfcLinkStatus.Unreadable
        }
        return true
    }

    private suspend fun link(tag: Tag) {
        _status.value = NfcLinkStatus.Writing
        _status.value = when (val result = linkNfcTag(tag, newInstall = offersRestore)) {
            is NfcLinkResult.Success -> NfcLinkStatus.Success(result.link.mode, carriesCode = result.link.code != null)
            is NfcLinkResult.Failure -> NfcLinkStatus.Error(result.reason)
            NfcLinkResult.Locked -> NfcLinkStatus.Locked
        }
    }

    fun restore() {
        if (_status.value !is NfcLinkStatus.OfferRestore) return
        _status.value = NfcLinkStatus.Restoring
        viewModelScope.launch {
            _status.value = when (val result = tagRestore.restore()) {
                is TagRestoreResult.Done -> NfcLinkStatus.Restored(result.hasBackup)
                TagRestoreResult.Failed -> NfcLinkStatus.CheckFailed
            }
        }
    }

    /**
     * The screen has moved on from a restore. Settled as a plain link, so coming Back to this step
     * shows the linked tag instead of replaying the restore's navigation.
     */
    fun restoreHandled() {
        if (_status.value is NfcLinkStatus.Restored) {
            _status.value = NfcLinkStatus.Success(TagLinkMode.SMART_NDEF, carriesCode = true)
        }
    }

    fun tryAgain() {
        _status.value = NfcLinkStatus.Checking
        viewModelScope.launch {
            // Nothing to offer any more (the identity went meanwhile): a blank tag, still to write.
            if (!show(tagRestore.recheck())) startFresh()
        }
    }

    fun startFresh() {
        _status.value = NfcLinkStatus.TapAgain
    }

    fun retry() {
        _status.value = NfcLinkStatus.WaitingForTap
    }

    override fun onCleared() {
        nfcTagBus.setMode(NfcBusMode.TOGGLE)
        super.onCleared()
    }

    private companion object {
        val BUSY = setOf(NfcLinkStatus.Checking, NfcLinkStatus.Writing, NfcLinkStatus.Restoring)
    }
}
