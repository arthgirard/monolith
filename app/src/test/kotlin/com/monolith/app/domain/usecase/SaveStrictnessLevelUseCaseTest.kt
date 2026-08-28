package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.StrictnessLevel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveStrictnessLevelUseCaseTest {

    private val strictnessRepository = FakeStrictnessRepository()

    private fun saveWith(active: Boolean) =
        SaveStrictnessLevelUseCase(strictnessRepository, FakeBlockRepository(initiallyActive = active))

    @Test
    fun `the level changes while monolith is off`() = runBlocking {
        assertTrue(saveWith(active = false)(StrictnessLevel.ABSOLUTE).isSuccess)

        assertEquals(StrictnessLevel.ABSOLUTE, strictnessRepository.current())
    }

    @Test
    fun `an active monolith refuses the change and keeps the stored level`() = runBlocking {
        assertTrue(saveWith(active = true)(StrictnessLevel.STRICT).isFailure)

        assertEquals(StrictnessLevel.STANDARD, strictnessRepository.current())
    }

    @Test
    fun `loosening is refused while active too, not only tightening`() = runBlocking {
        strictnessRepository.setStrictness(StrictnessLevel.ABSOLUTE)

        assertTrue(saveWith(active = true)(StrictnessLevel.STANDARD).isFailure)

        assertEquals(StrictnessLevel.ABSOLUTE, strictnessRepository.current())
    }
}
