package com.yuhan8954.persistence.sqlite

import com.yuhan8954.engine.model.*
import com.yuhan8954.history.MatchHistoryItem
import com.yuhan8954.history.MatchHistoryPage
import com.yuhan8954.history.UserMatchResult
import com.yuhan8954.history.UserStats
import com.yuhan8954.persistence.DatabaseFactory
import com.yuhan8954.room.FriendlyRoom
import com.yuhan8954.room.PlayerColors
import com.yuhan8954.room.RoomStatus
import com.yuhan8954.user.User
import com.yuhan8954.user.UserRole
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant

/** SQLite-backed application store using prepared statements and explicit transactions. */
class SqliteStore(private val databaseFactory: DatabaseFactory) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun upsertGoogleUser(subject: String, email: String?, displayName: String?, imageUrl: String?, now: Instant): User =
        databaseFactory.connection().use { connection ->
            connection.autoCommit = false
            val existing = findUserByGoogleSubject(connection, subject)
            val user = if (existing == null) {
                connection.prepareStatement(
                    """
                    INSERT INTO users(google_subject, email, display_name, profile_image_url, created_at, updated_at, last_login_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    java.sql.Statement.RETURN_GENERATED_KEYS,
                ).use { ps ->
                    ps.setString(1, subject)
                    ps.setString(2, email)
                    ps.setString(3, displayName)
                    ps.setString(4, imageUrl)
                    ps.setString(5, now.toString())
                    ps.setString(6, now.toString())
                    ps.setString(7, now.toString())
                    ps.executeUpdate()
                    val id = ps.generatedKeys.use { keys -> keys.next(); keys.getLong(1) }
                    User(id, subject, email, displayName, imageUrl, UserRole.USER, null, now, now, now)
                }
            } else {
                connection.prepareStatement(
                    """
                    UPDATE users SET email = ?, updated_at = ?, last_login_at = ?
                    WHERE id = ?
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, email)
                    ps.setString(2, now.toString())
                    ps.setString(3, now.toString())
                    ps.setLong(4, existing.id)
                    ps.executeUpdate()
                }
                existing.copy(email = email, updatedAt = now, lastLoginAt = now)
            }
            connection.commit()
            user
        }

    fun findUser(id: Long): User? = databaseFactory.connection().use { connection -> findUser(connection, id) }

    fun listUsers(page: Int, size: Int): List<User> =
        databaseFactory.connection().use { connection ->
            val limit = size.coerceIn(1, 100)
            val offset = page.coerceAtLeast(0) * limit
            connection.prepareStatement("SELECT * FROM users ORDER BY id ASC LIMIT ? OFFSET ?").use { ps ->
                ps.setInt(1, limit)
                ps.setInt(2, offset)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toUser()) } }
            }
        }

    fun setUserBan(userId: Long, bannedAt: Instant?): User? =
        databaseFactory.connection().use { connection ->
            connection.prepareStatement("UPDATE users SET banned_at = ?, updated_at = ? WHERE id = ?").use { ps ->
                ps.setString(1, bannedAt?.toString())
                ps.setString(2, Instant.now().toString())
                ps.setLong(3, userId)
                if (ps.executeUpdate() != 1) return@use null
            }
            findUser(connection, userId)
        }

    fun updateUserProfile(userId: Long, displayName: String?, profileImageUrl: String?, now: Instant): User? =
        databaseFactory.connection().use { connection ->
            connection.prepareStatement(
                """
                UPDATE users SET display_name = ?, profile_image_url = COALESCE(?, profile_image_url), updated_at = ?
                WHERE id = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, displayName)
                ps.setString(2, profileImageUrl)
                ps.setString(3, now.toString())
                ps.setLong(4, userId)
                if (ps.executeUpdate() != 1) return@use null
            }
            findUser(connection, userId)
        }

    fun ensureAiUser(now: Instant): User =
        upsertGoogleUser("local-ai-opponent", null, "Continuous Chess AI", null, now)

    fun createStandaloneGame(state: GameState, whiteUserId: Long, blackUserId: Long, now: Instant) {
        databaseFactory.connection().use { connection ->
            connection.autoCommit = false
            insertGame(connection, state, whiteUserId, blackUserId, state.status, now, now)
            connection.commit()
        }
    }

    fun createRoom(room: FriendlyRoom, initialState: GameState) {
        databaseFactory.connection().use { connection ->
            connection.autoCommit = false
            insertGame(connection, initialState, null, null, initialState.status, null, room.createdAt)
            connection.prepareStatement(
                """
                INSERT INTO friendly_rooms(invite_code, host_user_id, guest_user_id, game_id, status, created_at, joined_at, finished_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, room.inviteCode)
                ps.setLong(2, room.hostUserId)
                ps.setObject(3, room.guestUserId)
                ps.setString(4, room.gameId)
                ps.setString(5, room.status.name)
                ps.setString(6, room.createdAt.toString())
                ps.setString(7, room.joinedAt?.toString())
                ps.setString(8, room.finishedAt?.toString())
                ps.setString(9, room.expiresAt?.toString())
                ps.executeUpdate()
            }
            connection.commit()
        }
    }

    fun findRoomByInvite(inviteCode: String): FriendlyRoom? =
        databaseFactory.connection().use { connection -> findRoomByInvite(connection, inviteCode) }

    fun findRoomByGameId(gameId: String): FriendlyRoom? =
        databaseFactory.connection().use { connection ->
            connection.prepareStatement("SELECT * FROM friendly_rooms WHERE game_id = ?").use { ps ->
                ps.setString(1, gameId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toRoom() else null }
            }
        }

    fun startRoom(inviteCode: String, guestUserId: Long, colors: PlayerColors, activeState: GameState, now: Instant): FriendlyRoom? =
        databaseFactory.connection().use { connection ->
            connection.autoCommit = false
            val room = findRoomByInvite(connection, inviteCode) ?: return@use null
            connection.prepareStatement(
                """
                UPDATE friendly_rooms SET guest_user_id = ?, status = ?, joined_at = ?
                WHERE id = ? AND status = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, guestUserId)
                ps.setString(2, RoomStatus.ACTIVE.name)
                ps.setString(3, now.toString())
                ps.setLong(4, room.id)
                ps.setString(5, RoomStatus.WAITING.name)
                ps.executeUpdate()
            }
            updateGame(connection, activeState, colors.whiteUserId, colors.blackUserId, now, null)
            connection.commit()
            room.copy(guestUserId = guestUserId, status = RoomStatus.ACTIVE, joinedAt = now)
        }

    fun cancelRoom(inviteCode: String, userId: Long, now: Instant): Boolean =
        databaseFactory.connection().use { connection ->
            connection.autoCommit = false
            val room = findRoomByInvite(connection, inviteCode) ?: return@use false
            if (room.hostUserId != userId || room.status != RoomStatus.WAITING) return@use false
            val state = getGameState(connection, room.gameId) ?: return@use false
            val finished = state.copy(status = GameStatus.FINISHED, endReason = GameEndReason.ADMINISTRATIVE, result = GameResult.UNFINISHED)
            updateFinished(connection, room, finished, now)
            connection.prepareStatement("UPDATE friendly_rooms SET status = ?, finished_at = ? WHERE id = ?").use { ps ->
                ps.setString(1, RoomStatus.CANCELLED.name)
                ps.setString(2, now.toString())
                ps.setLong(3, room.id)
                ps.executeUpdate()
            }
            connection.commit()
            true
        }

    fun getGame(gameId: String): StoredGame? =
        databaseFactory.connection().use { connection -> getStoredGame(connection, gameId) }

    fun saveMove(state: GameState, move: MoveRecord) {
        databaseFactory.connection().use { connection ->
            connection.autoCommit = false
            insertMove(connection, state.gameId, move)
            val stored = getStoredGame(connection, state.gameId)
            updateGame(connection, state, stored?.whiteUserId, stored?.blackUserId, null, stored?.drawOfferByUserId)
            connection.commit()
        }
    }

    fun setDrawOffer(gameId: String, offeredByUserId: Long?) {
        databaseFactory.connection().use { connection ->
            connection.prepareStatement("UPDATE games SET draw_offer_by_user_id = ? WHERE id = ? AND status = ?").use { ps ->
                ps.setObject(1, offeredByUserId)
                ps.setString(2, gameId)
                ps.setString(3, GameStatus.ACTIVE.name)
                ps.executeUpdate()
            }
        }
    }

    fun finishGame(gameId: String, finalState: GameState, now: Instant): Boolean =
        databaseFactory.connection().use { connection ->
            connection.autoCommit = false
            val room = findRoomByGameId(connection, gameId)
            val updated = connection.prepareStatement(
                """
                UPDATE games SET status = ?, result = ?, end_reason = ?, current_turn = ?, state_version = ?,
                  white_in_check = ?, black_in_check = ?, state_json = ?, draw_offer_by_user_id = NULL,
                  finished_at = ?, final_move_count = ?
                WHERE id = ? AND status <> ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, finalState.status.name)
                ps.setString(2, finalState.result.name)
                ps.setString(3, finalState.endReason?.name)
                ps.setString(4, finalState.turn.name)
                ps.setLong(5, finalState.version)
                ps.setInt(6, if (finalState.whiteInCheck) 1 else 0)
                ps.setInt(7, if (finalState.blackInCheck) 1 else 0)
                ps.setString(8, json.encodeToString(finalState))
                ps.setString(9, now.toString())
                ps.setInt(10, finalState.moveHistory.size)
                ps.setString(11, gameId)
                ps.setString(12, GameStatus.FINISHED.name)
                ps.executeUpdate()
            }
            if (updated == 1 && room != null) {
                connection.prepareStatement("UPDATE friendly_rooms SET status = ?, finished_at = ? WHERE id = ?").use { ps ->
                    ps.setString(1, RoomStatus.FINISHED.name)
                    ps.setString(2, now.toString())
                    ps.setLong(3, room.id)
                    ps.executeUpdate()
                }
            }
            connection.commit()
            updated == 1
        }

    fun listMoves(gameId: String): List<MoveRecord> =
        databaseFactory.connection().use { connection ->
            connection.prepareStatement("SELECT * FROM moves WHERE game_id = ? ORDER BY sequence_number ASC").use { ps ->
                ps.setString(1, gameId)
                ps.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.toMove()) }
                }
            }
        }

    fun statsFor(userId: Long): UserStats =
        databaseFactory.connection().use { connection ->
            val games = finishedGamesFor(connection, userId)
            val opponents = games.map { if (it.whiteUserId == userId) it.blackUserId else it.whiteUserId }.filterNotNull().toSet().size
            UserStats(
                totalGames = games.size,
                wins = games.count { it.userResult(userId) == UserMatchResult.WIN },
                losses = games.count { it.userResult(userId) == UserMatchResult.LOSS },
                draws = games.count { it.userResult(userId) == UserMatchResult.DRAW },
                gamesAsWhite = games.count { it.whiteUserId == userId },
                gamesAsBlack = games.count { it.blackUserId == userId },
                resignations = games.count { it.endReason == GameEndReason.RESIGNATION.name },
                opponentsFaced = opponents,
            )
        }

    fun matchesFor(userId: Long, page: Int, size: Int): MatchHistoryPage =
        databaseFactory.connection().use { connection ->
            val offset = page.coerceAtLeast(0) * size.coerceIn(1, 100)
            connection.prepareStatement(
                """
                SELECT g.*, u.id AS opponent_id, u.display_name AS opponent_name, u.profile_image_url AS opponent_image
                FROM games g
                JOIN users u ON u.id = CASE WHEN g.white_user_id = ? THEN g.black_user_id ELSE g.white_user_id END
                WHERE g.status = ? AND (g.white_user_id = ? OR g.black_user_id = ?)
                ORDER BY g.finished_at DESC
                LIMIT ? OFFSET ?
                """.trimIndent(),
            ).use { ps ->
                val limit = size.coerceIn(1, 100)
                ps.setLong(1, userId)
                ps.setString(2, GameStatus.FINISHED.name)
                ps.setLong(3, userId)
                ps.setLong(4, userId)
                ps.setInt(5, limit)
                ps.setInt(6, offset)
                ps.executeQuery().use { rs ->
                    MatchHistoryPage(page, limit, buildList {
                        while (rs.next()) add(rs.toMatchHistoryItem(userId))
                    })
                }
            }
        }

    private fun insertGame(
        connection: Connection,
        state: GameState,
        whiteUserId: Long?,
        blackUserId: Long?,
        status: GameStatus,
        startedAt: Instant?,
        createdAt: Instant,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO games(id, white_user_id, black_user_id, status, result, end_reason, current_turn, state_version,
              white_in_check, black_in_check, state_json, created_at, started_at, final_move_count)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, state.gameId)
            ps.setObject(2, whiteUserId)
            ps.setObject(3, blackUserId)
            ps.setString(4, status.name)
            ps.setString(5, state.result.name)
            ps.setString(6, state.endReason?.name)
            ps.setString(7, state.turn.name)
            ps.setLong(8, state.version)
            ps.setInt(9, if (state.whiteInCheck) 1 else 0)
            ps.setInt(10, if (state.blackInCheck) 1 else 0)
            ps.setString(11, json.encodeToString(state.copy(status = status)))
            ps.setString(12, createdAt.toString())
            ps.setString(13, startedAt?.toString())
            ps.setInt(14, state.moveHistory.size)
            ps.executeUpdate()
        }
    }

    private fun updateGame(connection: Connection, state: GameState, whiteUserId: Long?, blackUserId: Long?, startedAt: Instant?, drawOfferBy: Long?) {
        connection.prepareStatement(
            """
            UPDATE games SET white_user_id = COALESCE(?, white_user_id), black_user_id = COALESCE(?, black_user_id),
              status = ?, result = ?, end_reason = ?, current_turn = ?, state_version = ?, white_in_check = ?,
              black_in_check = ?, state_json = ?, draw_offer_by_user_id = ?, started_at = COALESCE(?, started_at),
              final_move_count = ?
            WHERE id = ?
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, whiteUserId)
            ps.setObject(2, blackUserId)
            ps.setString(3, state.status.name)
            ps.setString(4, state.result.name)
            ps.setString(5, state.endReason?.name)
            ps.setString(6, state.turn.name)
            ps.setLong(7, state.version)
            ps.setInt(8, if (state.whiteInCheck) 1 else 0)
            ps.setInt(9, if (state.blackInCheck) 1 else 0)
            ps.setString(10, json.encodeToString(state))
            ps.setObject(11, drawOfferBy)
            ps.setString(12, startedAt?.toString())
            ps.setInt(13, state.moveHistory.size)
            ps.setString(14, state.gameId)
            ps.executeUpdate()
        }
    }

    private fun insertMove(connection: Connection, gameId: String, move: MoveRecord) {
        connection.prepareStatement(
            """
            INSERT OR IGNORE INTO moves(game_id, sequence_number, piece_id, piece_type, piece_color,
              start_x_expression, start_y_expression, start_x_value, start_y_value,
              end_x_expression, end_y_expression, end_x_value, end_y_value, captured_piece_id, special_move, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, gameId)
            ps.setInt(2, move.sequence)
            ps.setString(3, move.pieceId)
            ps.setString(4, move.pieceType.name)
            ps.setString(5, move.color.name)
            ps.setString(6, move.start.x.expression)
            ps.setString(7, move.start.y.expression)
            ps.setDouble(8, move.start.x.numericValue)
            ps.setDouble(9, move.start.y.numericValue)
            ps.setString(10, move.end.x.expression)
            ps.setString(11, move.end.y.expression)
            ps.setDouble(12, move.end.x.numericValue)
            ps.setDouble(13, move.end.y.numericValue)
            ps.setString(14, move.capturedPieceId)
            ps.setString(15, move.specialMove?.name)
            ps.setString(16, move.createdAt.toString())
            ps.executeUpdate()
        }
    }

    private fun updateFinished(connection: Connection, room: FriendlyRoom, finalState: GameState, now: Instant) {
        updateGame(connection, finalState, null, null, null, null)
        connection.prepareStatement("UPDATE games SET finished_at = ? WHERE id = ?").use { ps ->
            ps.setString(1, now.toString())
            ps.setString(2, room.gameId)
            ps.executeUpdate()
        }
    }

    private fun findUserByGoogleSubject(connection: Connection, subject: String): User? =
        connection.prepareStatement("SELECT * FROM users WHERE google_subject = ?").use { ps ->
            ps.setString(1, subject)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toUser() else null }
        }

    private fun findUser(connection: Connection, id: Long): User? =
        connection.prepareStatement("SELECT * FROM users WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toUser() else null }
        }

    private fun findRoomByInvite(connection: Connection, inviteCode: String): FriendlyRoom? =
        connection.prepareStatement("SELECT * FROM friendly_rooms WHERE invite_code = ?").use { ps ->
            ps.setString(1, inviteCode)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toRoom() else null }
        }

    private fun findRoomByGameId(connection: Connection, gameId: String): FriendlyRoom? =
        connection.prepareStatement("SELECT * FROM friendly_rooms WHERE game_id = ?").use { ps ->
            ps.setString(1, gameId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toRoom() else null }
        }

    private fun getGameState(connection: Connection, gameId: String): GameState? =
        connection.prepareStatement("SELECT state_json FROM games WHERE id = ?").use { ps ->
            ps.setString(1, gameId)
            ps.executeQuery().use { rs -> if (rs.next()) json.decodeFromString<GameState>(rs.getString("state_json")) else null }
        }

    private fun getStoredGame(connection: Connection, gameId: String): StoredGame? =
        connection.prepareStatement("SELECT * FROM games WHERE id = ?").use { ps ->
            ps.setString(1, gameId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toStoredGame() else null }
        }

    private fun finishedGamesFor(connection: Connection, userId: Long): List<StoredGame> =
        connection.prepareStatement("SELECT * FROM games WHERE status = ? AND (white_user_id = ? OR black_user_id = ?)").use { ps ->
            ps.setString(1, GameStatus.FINISHED.name)
            ps.setLong(2, userId)
            ps.setLong(3, userId)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toStoredGame()) } }
        }

    private fun ResultSet.toUser(): User = User(
        id = getLong("id"),
        googleSubject = getString("google_subject"),
        email = getString("email"),
        displayName = getString("display_name"),
        profileImageUrl = getString("profile_image_url"),
        role = runCatching { UserRole.valueOf(getString("role") ?: UserRole.USER.name) }.getOrDefault(UserRole.USER),
        bannedAt = getNullableInstant("banned_at"),
        createdAt = parseDbInstant(getString("created_at")),
        updatedAt = parseDbInstant(getString("updated_at")),
        lastLoginAt = parseDbInstant(getString("last_login_at")),
    )

    private fun ResultSet.toRoom(): FriendlyRoom = FriendlyRoom(
        id = getLong("id"),
        inviteCode = getString("invite_code"),
        hostUserId = getLong("host_user_id"),
        guestUserId = getNullableLong("guest_user_id"),
        gameId = getString("game_id"),
        status = RoomStatus.valueOf(getString("status")),
        createdAt = parseDbInstant(getString("created_at")),
        joinedAt = getNullableInstant("joined_at"),
        finishedAt = getNullableInstant("finished_at"),
        expiresAt = getNullableInstant("expires_at"),
    )

    private fun ResultSet.toStoredGame(): StoredGame = StoredGame(
        id = getString("id"),
        whiteUserId = getNullableLong("white_user_id"),
        blackUserId = getNullableLong("black_user_id"),
        state = json.decodeFromString(getString("state_json")),
        status = getString("status"),
        result = getString("result"),
        endReason = getString("end_reason"),
        drawOfferByUserId = getNullableLong("draw_offer_by_user_id"),
        startedAt = getNullableInstant("started_at"),
        finishedAt = getNullableInstant("finished_at"),
        finalMoveCount = getInt("final_move_count"),
    )

    private fun ResultSet.toMove(): MoveRecord = MoveRecord(
        sequence = getInt("sequence_number"),
        pieceId = getString("piece_id"),
        pieceType = PieceType.valueOf(getString("piece_type")),
        color = PieceColor.valueOf(getString("piece_color")),
        start = Position(
            Coordinate(getString("start_x_expression"), getString("start_x_expression"), getDouble("start_x_value")),
            Coordinate(getString("start_y_expression"), getString("start_y_expression"), getDouble("start_y_value")),
        ),
        end = Position(
            Coordinate(getString("end_x_expression"), getString("end_x_expression"), getDouble("end_x_value")),
            Coordinate(getString("end_y_expression"), getString("end_y_expression"), getDouble("end_y_value")),
        ),
        xExpression = getString("end_x_expression"),
        yExpression = getString("end_y_expression"),
        capturedPieceId = getString("captured_piece_id"),
        specialMove = getString("special_move")?.let(SpecialMoveType::valueOf),
        createdAt = parseDbInstant(getString("created_at")),
    )

    private fun ResultSet.toMatchHistoryItem(userId: Long): MatchHistoryItem {
        val game = toStoredGame()
        val color = if (game.whiteUserId == userId) PieceColor.WHITE else PieceColor.BLACK
        return MatchHistoryItem(
            gameId = game.id,
            opponentUserId = getLong("opponent_id"),
            opponentDisplayName = getString("opponent_name"),
            opponentProfileImageUrl = getString("opponent_image"),
            userColor = color,
            result = game.userResult(userId),
            endReason = GameEndReason.valueOf(game.endReason ?: GameEndReason.UNKNOWN.name),
            moveCount = game.finalMoveCount,
            startedAt = game.startedAt ?: game.finishedAt ?: Instant.EPOCH,
            finishedAt = game.finishedAt ?: Instant.EPOCH,
        )
    }

    private fun ResultSet.getNullableLong(column: String): Long? {
        val value = getLong(column)
        return if (wasNull()) null else value
    }

    private fun ResultSet.getNullableInstant(column: String): Instant? = getString(column)?.let(::parseDbInstant)

    private fun parseDbInstant(value: String): Instant =
        runCatching { Instant.parse(value) }.getOrElse {
            Instant.parse(value.replace(' ', 'T') + "Z")
        }
}

/** Persisted game row plus decoded state snapshot. */
data class StoredGame(
    val id: String,
    val whiteUserId: Long?,
    val blackUserId: Long?,
    val state: GameState,
    val status: String,
    val result: String,
    val endReason: String?,
    val drawOfferByUserId: Long?,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val finalMoveCount: Int,
) {
    fun colorOf(userId: Long): PieceColor? = when (userId) {
        whiteUserId -> PieceColor.WHITE
        blackUserId -> PieceColor.BLACK
        else -> null
    }

    fun userResult(userId: Long): UserMatchResult = when (GameResult.valueOf(result)) {
        GameResult.DRAW -> UserMatchResult.DRAW
        GameResult.WHITE_WIN -> if (whiteUserId == userId) UserMatchResult.WIN else UserMatchResult.LOSS
        GameResult.BLACK_WIN -> if (blackUserId == userId) UserMatchResult.WIN else UserMatchResult.LOSS
        GameResult.UNFINISHED -> UserMatchResult.DRAW
    }
}
