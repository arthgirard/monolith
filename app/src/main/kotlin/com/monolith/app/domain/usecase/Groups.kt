package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.GroupInfo
import com.monolith.app.domain.model.GroupLabel
import com.monolith.app.domain.model.ShareSettings

/** A signal leaves the phone only if at least one group may see it. */
fun shareUnion(groups: List<GroupInfo>): ShareSettings = ShareSettings(
    saved = groups.any { it.share.saved },
    streak = groups.any { it.share.streak },
    pauses = groups.any { it.share.pauses },
)

/**
 * Two people: the group is the other person. Three or more: its shared name, else everyone
 * else's names. Alone (just created): the invite code, which is what you'd share next anyway.
 */
fun groupLabel(group: GroupInfo): GroupLabel = when {
    group.memberCount <= 1 || group.otherMembers.isEmpty() -> GroupLabel(group.inviteCode, isCode = true)
    group.memberCount == 2 -> GroupLabel(group.otherMembers.first(), isCode = false)
    else -> GroupLabel(group.name ?: group.otherMembers.sorted().joinToString(", "), isCode = false)
}
