package com.yuhan8954.room

import com.yuhan8954.engine.model.InstantIsoSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

/** Friendly invite room lifecycle. */
@Serializable
enum class RoomStatus { WAITING, ACTIVE, FINISHED, EXPIRED, CANCELLED }

/** Friendly room persisted in SQLite. */
@Serializable
data class FriendlyRoom(
    val id: Long,
    val inviteCode: String,
    val hostUserId: Long,
    val guestUserId: Long?,
    val gameId: String,
    val status: RoomStatus,
    @Serializable(with = InstantIsoSerializer::class)
    val createdAt: Instant,
    @Serializable(with = InstantIsoSerializer::class)
    val joinedAt: Instant?,
    @Serializable(with = InstantIsoSerializer::class)
    val finishedAt: Instant?,
    @Serializable(with = InstantIsoSerializer::class)
    val expiresAt: Instant?,
)

/** Color assignment result for two players. */
@Serializable
data class PlayerColors(
    val whiteUserId: Long,
    val blackUserId: Long,
)

/** Strategy contract for friendly-game color assignment. */
interface ColorAssignmentStrategy {
    fun assign(hostUserId: Long, guestUserId: Long): PlayerColors
}

/** MVP policy: room host is white. */
class HostPlaysWhiteStrategy : ColorAssignmentStrategy {
    override fun assign(hostUserId: Long, guestUserId: Long): PlayerColors = PlayerColors(hostUserId, guestUserId)
}
