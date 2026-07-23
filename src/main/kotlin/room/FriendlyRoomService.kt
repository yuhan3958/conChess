package com.yuhan8954.room

import com.yuhan8954.application.AppConfig
import com.yuhan8954.engine.model.GameStatus
import com.yuhan8954.engine.service.ContinuousChessEngine
import com.yuhan8954.persistence.sqlite.SqliteStore
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** Creates and joins invite-code friendly rooms. */
class FriendlyRoomService(
    private val store: SqliteStore,
    private val engine: ContinuousChessEngine,
    private val inviteCodeGenerator: InviteCodeGenerator,
    private val colors: ColorAssignmentStrategy,
    private val config: AppConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun createRoom(hostUserId: Long): FriendlyRoomView {
        val now = clock.instant()
        val invite = generateUniqueInvite()
        val gameId = UUID.randomUUID().toString()
        val room = FriendlyRoom(
            id = 0L,
            inviteCode = invite,
            hostUserId = hostUserId,
            guestUserId = null,
            gameId = gameId,
            status = RoomStatus.WAITING,
            createdAt = now,
            joinedAt = null,
            finishedAt = null,
            expiresAt = now.plus(Duration.ofHours(24)),
        )
        store.createRoom(room, engine.createInitialState(gameId, GameStatus.WAITING_FOR_OPPONENT))
        return room.toView(config.appBaseUrl)
    }

    fun getRoom(inviteCode: String): FriendlyRoomView? = store.findRoomByInvite(inviteCode)?.toView(config.appBaseUrl)

    fun join(inviteCode: String, guestUserId: Long): JoinRoomResult {
        val room = store.findRoomByInvite(inviteCode) ?: return JoinRoomResult.Failure("ROOM_NOT_FOUND", "Room was not found.")
        if (room.expiresAt != null && room.expiresAt.isBefore(clock.instant())) {
            return JoinRoomResult.Failure("ROOM_EXPIRED", "Room is expired.")
        }
        if (room.hostUserId == guestUserId) {
            return JoinRoomResult.Failure("SELF_JOIN_NOT_ALLOWED", "You cannot join your own room as the second player.")
        }
        if (room.status != RoomStatus.WAITING || room.guestUserId != null) {
            return JoinRoomResult.Failure("ROOM_NOT_JOINABLE", "Room is not joinable.")
        }
        val assigned = colors.assign(room.hostUserId, guestUserId)
        val active = engine.createInitialState(room.gameId, GameStatus.ACTIVE)
        val started = store.startRoom(inviteCode, guestUserId, assigned, active, clock.instant())
            ?: return JoinRoomResult.Failure("ROOM_NOT_JOINABLE", "Room is not joinable.")
        return JoinRoomResult.Success(started.toView(config.appBaseUrl), assigned)
    }

    fun cancel(inviteCode: String, userId: Long): Boolean = store.cancelRoom(inviteCode, userId, clock.instant())

    private fun generateUniqueInvite(): String {
        repeat(16) {
            val code = inviteCodeGenerator.generate()
            if (store.findRoomByInvite(code) == null) return code
        }
        error("Could not generate unique invite code.")
    }
}

/** API view for a friendly room. */
@kotlinx.serialization.Serializable
data class FriendlyRoomView(
    val inviteCode: String,
    val inviteLink: String,
    val hostUserId: Long,
    val guestUserId: Long?,
    val gameId: String,
    val status: RoomStatus,
)

/** Join-room service outcome. */
sealed interface JoinRoomResult {
    data class Success(val room: FriendlyRoomView, val colors: PlayerColors) : JoinRoomResult
    data class Failure(val code: String, val message: String) : JoinRoomResult
}

private fun FriendlyRoom.toView(baseUrl: String): FriendlyRoomView = FriendlyRoomView(
    inviteCode = inviteCode,
    inviteLink = "${baseUrl.trimEnd('/')}/game/join/$inviteCode",
    hostUserId = hostUserId,
    guestUserId = guestUserId,
    gameId = gameId,
    status = status,
)
