package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.ImportantPerson
import com.monolith.app.domain.repository.ImportantPersonRepository
import javax.inject.Inject

/**
 * Corrects an existing entry: a misspelt name, a handle that changed, the wrong app picked.
 * Editable while Monolith is active, for the same reason as [AddImportantPersonUseCase].
 */
class UpdateImportantPersonUseCase @Inject constructor(
    private val importantPersonRepository: ImportantPersonRepository,
) {
    suspend operator fun invoke(original: ImportantPerson, updated: ImportantPerson): Result<Unit> {
        if (updated.name.isNullOrBlank() && updated.handle.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("Enter a name or a handle."))
        }
        importantPersonRepository.updateImportantPerson(original, updated)
        return Result.success(Unit)
    }
}
