package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.ImportantPerson
import com.monolith.app.domain.repository.BlockRepository
import com.monolith.app.domain.repository.ImportantPersonRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Rejects edits while Monolith is active, so the tag stays the only way out. */
class RemoveImportantPersonUseCase @Inject constructor(
    private val importantPersonRepository: ImportantPersonRepository,
    private val blockRepository: BlockRepository,
) {
    suspend operator fun invoke(person: ImportantPerson): Result<Unit> {
        val state = blockRepository.observeBlockState().first()
        if (state.isActive) {
            return Result.failure(IllegalStateException("Monolith is active; unlock with your tag first."))
        }
        importantPersonRepository.removeImportantPerson(person)
        return Result.success(Unit)
    }
}
