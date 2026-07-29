package com.monolith.app.ui.nfclink

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monolith.app.domain.model.NfcLinkResult
import com.monolith.app.domain.model.TagLinkMode
import com.monolith.app.domain.usecase.LinkNfcTagUseCase
import com.monolith.app.nfc.NfcBusMode
import com.monolith.app.nfc.NfcManager
import com.monolith.app.nfc.NfcTagBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

sealed interface NfcLinkStatus {
    data object NfcUnsupported : NfcLinkStatus
    data object WaitingForTap : NfcLinkStatus
    data object Writing : NfcLinkStatus
    data class Success(val mode: TagLinkMode) : NfcLinkStatus
    data class Error(val message: String) : NfcLinkStatus
}

@HiltViewModel
class NfcLinkViewModel @Inject constructor(
    private val linkNfcTag: LinkNfcTagUseCase,
    private val nfcTagBus: NfcTagBus,
    nfcManager: NfcManager,
) : ViewModel() {

    private val _status = MutableStateFlow<NfcLinkStatus>(
        if (nfcManager.isNfcSupported) NfcLinkStatus.WaitingForTap else NfcLinkStatus.NfcUnsupported,
    )
    val status: StateFlow<NfcLinkStatus> = _status

    init {
        nfcTagBus.setMode(NfcBusMode.LINKING)

        nfcTagBus.tagEvents.onEach { tag ->
            _status.value = NfcLinkStatus.Writing
            when (val result = linkNfcTag(tag)) {
                is NfcLinkResult.Success -> _status.value = NfcLinkStatus.Success(result.link.mode)
                is NfcLinkResult.Failure -> _status.value = NfcLinkStatus.Error(result.reason)
            }
        }.launchIn(viewModelScope)
    }

    fun retry() {
        _status.value = NfcLinkStatus.WaitingForTap
    }

    override fun onCleared() {
        nfcTagBus.setMode(NfcBusMode.TOGGLE)
        super.onCleared()
    }
}
