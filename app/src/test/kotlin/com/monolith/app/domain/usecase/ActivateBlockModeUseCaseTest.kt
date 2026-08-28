package com.monolith.app.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivateBlockModeUseCaseTest {

    private val appUnlockRepository = FakeAppUnlockRepository()

    private fun activateWith(blockRepository: FakeBlockRepository) =
        ActivateBlockModeUseCase(blockRepository, appUnlockRepository)

    @Test
    fun `a linked tag turns monolith on`() = runBlocking {
        val blockRepository = FakeBlockRepository()

        assertTrue(activateWith(blockRepository)())
        assertTrue(blockRepository.observeBlockState().first().isActive)
    }

    @Test
    fun `no tag means no lock, since nothing would open it`() = runBlocking {
        val blockRepository = FakeBlockRepository(linkedTag = null)

        assertFalse(activateWith(blockRepository)())
        assertFalse(blockRepository.observeBlockState().first().isActive)
        assertEquals(0, blockRepository.sessionStartCount)
    }

    @Test
    fun `activating an already-active monolith changes nothing`() = runBlocking {
        val blockRepository = FakeBlockRepository(initiallyActive = true)

        assertFalse(activateWith(blockRepository)())
        assertEquals(0, appUnlockRepository.clearUnlocksCount)
    }
}
