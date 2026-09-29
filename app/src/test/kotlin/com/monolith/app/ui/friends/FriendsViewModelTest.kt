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
    fun `group limits get their own messages and a stale group reads as generic`() {
        assertEquals(FriendsMessage.TOO_MANY_GROUPS, FriendsMessage.of(LeaderboardError.TOO_MANY_GROUPS))
        assertEquals(FriendsMessage.ALREADY_MEMBER, FriendsMessage.of(LeaderboardError.ALREADY_MEMBER))
        assertEquals(FriendsMessage.NAME_NEEDS_THREE, FriendsMessage.of(LeaderboardError.NAME_NEEDS_THREE))
        assertEquals(FriendsMessage.GENERIC, FriendsMessage.of(LeaderboardError.NOT_MEMBER))
    }
}
