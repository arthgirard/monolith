package com.monolith.app.ui.friends

import com.monolith.app.domain.model.LeaderboardError
import org.junit.Assert.assertEquals
import org.junit.Test

class FriendsViewModelTest {

    @Test
    fun `an unknown invite and a full group get their own messages`() {
        assertEquals(FriendsMessage.INVITE_NOT_FOUND, FriendsMessage.of(LeaderboardError.INVITE_NOT_FOUND))
        assertEquals(FriendsMessage.GROUP_FULL, FriendsMessage.of(LeaderboardError.GROUP_FULL))
        assertEquals(FriendsMessage.NETWORK, FriendsMessage.of(LeaderboardError.NETWORK))
        assertEquals(FriendsMessage.REMOVED, FriendsMessage.of(LeaderboardError.UNAUTHORIZED))
        assertEquals(FriendsMessage.GENERIC, FriendsMessage.of(LeaderboardError.SERVER))
    }

    @Test
    fun `a failed restore reads as a bad recovery code, not as removal`() {
        assertEquals(FriendsMessage.BAD_RECOVERY, FriendsMessage.ofRestore(LeaderboardError.UNAUTHORIZED))
        assertEquals(FriendsMessage.NETWORK, FriendsMessage.ofRestore(LeaderboardError.NETWORK))
    }
}
