package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.ImportantPerson
import com.monolith.app.domain.repository.ImportantPersonRepository
import javax.inject.Inject

/**
 * Editable while Monolith is active. This list only decides whose notifications get through a
 * block; it opens no app, so it isn't a way out of one, and the moment someone actually needs
 * is the moment they are blocked and a person they care about is missing from it.
 */
class AddImportantPersonUseCase @Inject constructor(
    private val importantPersonRepository: ImportantPersonRepository,
) {
    suspend operator fun invoke(person: ImportantPerson): Result<Unit> {
        if (person.name.isNullOrBlank() && person.handle.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("Enter a name or a handle."))
        }
        importantPersonRepository.addImportantPerson(person)
        return Result.success(Unit)
    }
}
