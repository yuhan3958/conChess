package auth

import com.yuhan8954.application.AppConfig
import com.yuhan8954.auth.GoogleOAuthService
import com.yuhan8954.auth.GoogleProfile
import com.yuhan8954.auth.OAuthClient
import com.yuhan8954.persistence.DatabaseFactory
import com.yuhan8954.persistence.migration.SchemaMigrator
import com.yuhan8954.persistence.sqlite.SqliteStore
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals

class GoogleOAuthServiceTest {
    @Test
    fun `login creates and updates user by google subject`() {
        val db = createTempFile(suffix = ".sqlite").toFile()
        val factory = DatabaseFactory(db.absolutePath)
        SchemaMigrator(factory).migrate()
        val store = SqliteStore(factory)
        val service = GoogleOAuthService(
            AppConfig("client", "secret", "http://localhost/callback", "session-secret", db.absolutePath, "http://localhost", "test"),
            store,
            object : OAuthClient {
                override fun exchangeAndVerify(code: String, expectedState: String): GoogleProfile =
                    GoogleProfile("stable-sub", "a@example.com", "Alice", null)
            },
        )
        val first = service.finishLogin("code", "state")
        val second = service.finishLogin("code", "state")
        assertEquals(first, second)
        assertEquals("Alice", store.findUser(first)?.displayName)
    }
}
