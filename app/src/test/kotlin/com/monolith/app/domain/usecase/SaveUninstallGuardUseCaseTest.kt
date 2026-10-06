package com.monolith.app.domain.usecase

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveUninstallGuardUseCaseTest {

    private val strictnessRepository = FakeStrictnessRepository()

    private fun saveWith(active: Boolean) =
        SaveUninstallGuardUseCase(strictnessRepository, FakeBlockRepository(initiallyActive = active))

    @Test
    fun `the guard is on until someone turns it off`() {
        assertTrue(strictnessRepository.currentGuard())
    }

    @Test
    fun `the guard can be turned off while monolith is off`() = runBlocking {
        assertTrue(saveWith(active = false)(false).isSuccess)

        assertFalse(strictnessRepository.currentGuard())
    }

    @Test
    fun `an active monolith refuses to drop its own guard`() = runBlocking {
        assertTrue(saveWith(active = true)(false).isFailure)

        assertTrue(strictnessRepository.currentGuard())
    }
}
