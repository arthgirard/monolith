package com.monolith.app.domain.usecase

import com.monolith.app.domain.repository.StrictnessRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveUninstallGuardUseCase @Inject constructor(
    private val strictnessRepository: StrictnessRepository,
) {
    operator fun invoke(): Flow<Boolean> = strictnessRepository.observeUninstallGuard()
}
