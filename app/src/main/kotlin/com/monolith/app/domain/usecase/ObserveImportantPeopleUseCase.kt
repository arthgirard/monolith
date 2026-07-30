package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.ImportantPerson
import com.monolith.app.domain.repository.ImportantPersonRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveImportantPeopleUseCase @Inject constructor(
    private val importantPersonRepository: ImportantPersonRepository,
) {
    operator fun invoke(): Flow<List<ImportantPerson>> = importantPersonRepository.observeImportantPeople()
}
