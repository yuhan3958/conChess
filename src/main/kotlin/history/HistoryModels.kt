package com.yuhan8954.history

import com.yuhan8954.engine.model.GameEndReason
import com.yuhan8954.engine.model.InstantIsoSerializer
import com.yuhan8954.engine.model.PieceColor
import kotlinx.serialization.Serializable
import java.time.Instant

/** Aggregate match statistics from the user's perspective. */
@Serializable
data class UserStats(
    val totalGames: Int,
    val wins: Int,
    val losses: Int,
    val draws: Int,
    val gamesAsWhite: Int,
    val gamesAsBlack: Int,
    val resignations: Int,
    val opponentsFaced: Int,
)

/** User-relative match result. */
@Serializable
enum class UserMatchResult { WIN, LOSS, DRAW }

/** Recent match item from the user's perspective. */
@Serializable
data class MatchHistoryItem(
    val gameId: String,
    val opponentUserId: Long,
    val opponentDisplayName: String?,
    val opponentProfileImageUrl: String?,
    val userColor: PieceColor,
    val result: UserMatchResult,
    val endReason: GameEndReason,
    val moveCount: Int,
    @Serializable(with = InstantIsoSerializer::class)
    val startedAt: Instant,
    @Serializable(with = InstantIsoSerializer::class)
    val finishedAt: Instant,
)

/** Paginated recent-match response. */
@Serializable
data class MatchHistoryPage(
    val page: Int,
    val size: Int,
    val items: List<MatchHistoryItem>,
)
