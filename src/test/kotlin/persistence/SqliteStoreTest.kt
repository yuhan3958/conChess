package persistence

import com.yuhan8954.engine.model.GameStatus
import com.yuhan8954.engine.model.PieceColor
import com.yuhan8954.engine.service.ContinuousChessEngine
import com.yuhan8954.persistence.DatabaseFactory
import com.yuhan8954.persistence.migration.SchemaMigrator
import com.yuhan8954.persistence.sqlite.SqliteStore
import com.yuhan8954.room.FriendlyRoom
import com.yuhan8954.room.PlayerColors
import com.yuhan8954.room.RoomStatus
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SqliteStoreTest {
    @Test
    fun `stores users rooms games and aggregates stats`() {
        val db = createTempFile(suffix = ".sqlite").toFile()
        val factory = DatabaseFactory(db.absolutePath)
        SchemaMigrator(factory).migrate()
        val store = SqliteStore(factory)
        val engine = ContinuousChessEngine(com.yuhan8954.engine.parser.SafeMathExpressionParser())
        val now = java.time.Instant.parse("2026-01-01T00:00:00Z")
        val host = store.upsertGoogleUser("sub-host", "h@example.com", "Host", null, now)
        val guest = store.upsertGoogleUser("sub-guest", "g@example.com", "Guest", null, now)
        val room = FriendlyRoom(0, "invite", host.id, null, "game", RoomStatus.WAITING, now, null, null, now.plusSeconds(60))
        store.createRoom(room, engine.createInitialState("game", GameStatus.WAITING_FOR_OPPONENT))
        val started = assertNotNull(store.startRoom("invite", guest.id, PlayerColors(host.id, guest.id), engine.createInitialState("game", GameStatus.ACTIVE), now))
        assertEquals(RoomStatus.ACTIVE, started.status)
        val finalState = store.getGame("game")!!.state.copy(
            status = com.yuhan8954.engine.model.GameStatus.FINISHED,
            result = com.yuhan8954.engine.model.GameResult.WHITE_WIN,
            endReason = com.yuhan8954.engine.model.GameEndReason.RESIGNATION,
        )
        store.finishGame("game", finalState, now.plusSeconds(120))
        val stats = store.statsFor(host.id)
        assertEquals(1, stats.totalGames)
        assertEquals(1, stats.wins)
        assertEquals(1, stats.gamesAsWhite)
        assertEquals(PieceColor.WHITE, store.matchesFor(host.id, 0, 20).items.single().userColor)
    }
}
