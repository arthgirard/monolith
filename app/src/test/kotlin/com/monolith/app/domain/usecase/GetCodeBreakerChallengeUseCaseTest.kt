package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.CodeBreaker
import com.monolith.app.domain.model.StrictnessLevel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GetCodeBreakerChallengeUseCaseTest {

    private val appUnlockRepository = FakeAppUnlockRepository()
    private val packageName = "com.example.blocked"

    private fun challengeAt(level: StrictnessLevel) =
        GetCodeBreakerChallengeUseCase(appUnlockRepository, FakeStrictnessRepository(level))

    @Test
    fun `standard hands out a puzzle`() = runBlocking {
        assertNotNull(challengeAt(StrictnessLevel.STANDARD)(packageName))
    }

    @Test
    fun `standard hands back the solved puzzle still waiting on its waiver`() = runBlocking {
        val secret = listOf(0, 1, 2, 3, 4, 5)
        val solved = CodeBreaker(secret = secret).withGuess(secret)
        appUnlockRepository.saveCodeBreaker(packageName, solved)

        assertEquals(solved, challengeAt(StrictnessLevel.STANDARD)(packageName))
    }

    @Test
    fun `strict has no puzzle to offer and writes none`() = runBlocking {
        assertNull(challengeAt(StrictnessLevel.STRICT)(packageName))

        assertTrue(appUnlockRepository.observeCodeBreakers().first().isEmpty())
    }

    @Test
    fun `absolute has no puzzle to offer and writes none`() = runBlocking {
        assertNull(challengeAt(StrictnessLevel.ABSOLUTE)(packageName))

        assertTrue(appUnlockRepository.observeCodeBreakers().first().isEmpty())
    }
}
