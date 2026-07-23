package game

import com.yuhan8954.engine.model.PieceColor
import com.yuhan8954.engine.parser.SafeMathExpressionParser
import com.yuhan8954.engine.service.ContinuousChessEngine
import com.yuhan8954.game.CreateAiGameOptions
import com.yuhan8954.game.GameApplicationService
import com.yuhan8954.game.ai.AiDifficulty
import com.yuhan8954.game.ai.AiMovePlanner
import com.yuhan8954.persistence.DatabaseFactory
import com.yuhan8954.persistence.migration.SchemaMigrator
import com.yuhan8954.persistence.sqlite.SqliteStore
import java.time.Instant
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiGameServiceTest {
    @Test
    fun `creates ai game with user black and ai opening move`() {
        val db = createTempFile(suffix = ".sqlite").toFile()
        val factory = DatabaseFactory(db.absolutePath)
        SchemaMigrator(factory).migrate()
        val store = SqliteStore(factory)
        val user = store.upsertGoogleUser("human", "h@example.com", "Human", null, Instant.EPOCH)
        val ai = store.ensureAiUser(Instant.EPOCH)
        val engine = ContinuousChessEngine(SafeMathExpressionParser())
        val service = GameApplicationService(store, engine, ai.id, AiMovePlanner(engine))

        val view = service.createAiGame(
            user.id,
            CreateAiGameOptions(PieceColor.BLACK, initialSeconds = 180, incrementSeconds = 2, difficulty = AiDifficulty.HARD),
        )

        assertEquals(PieceColor.BLACK, view.yourColor)
        assertEquals(AiDifficulty.HARD, view.aiDifficulty)
        assertEquals(PieceColor.BLACK, view.state.turn)
        assertTrue(view.state.moveHistory.isNotEmpty())
        assertEquals(180, view.state.clock?.timeControl?.initialSeconds)
        assertEquals(2, view.state.clock?.timeControl?.incrementSeconds)
    }
}
