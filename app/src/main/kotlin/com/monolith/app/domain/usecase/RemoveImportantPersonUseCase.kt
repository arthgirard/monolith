package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.ImportantPerson
import com.monolith.app.domain.repository.ImportantPersonRepository
import javax.inject.Inject

/** Editable while Monolith is active, for the same reason as [AddImportantPersonUseCase]. */
class RemoveImportantPersonUseCase @Inject constructor(
    private val importantPersonRepository: ImportantPersonRepository,
) {
    suspend operator fun invoke(person: ImportantPerson): Result<Unit> {
        importantPersonRepository.removeImportantPerson(person)
        return Result.success(Unit)
    }
}
