package com.yuhan8954.persistence.migration

import com.yuhan8954.persistence.DatabaseFactory
import java.time.Instant

/** Minimal append-only SQLite migration runner preserving existing data. */
class SchemaMigrator(private val databaseFactory: DatabaseFactory) {
    fun migrate() {
        databaseFactory.connection().use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                      version INTEGER PRIMARY KEY,
                      applied_at TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
            }
            val applied = connection.prepareStatement("SELECT version FROM schema_migrations").use { ps ->
                ps.executeQuery().use { rs ->
                    buildSet {
                        while (rs.next()) add(rs.getInt("version"))
                    }
                }
            }
            migrations.filterKeys { it !in applied }.toSortedMap().forEach { (version, sql) ->
                connection.createStatement().use { statement ->
                    sql.split("-- statement").map { it.trim() }.filter { it.isNotEmpty() }.forEach(statement::execute)
                }
                connection.prepareStatement("INSERT INTO schema_migrations(version, applied_at) VALUES (?, ?)").use { ps ->
                    ps.setInt(1, version)
                    ps.setString(2, Instant.now().toString())
                    ps.executeUpdate()
                }
            }
            connection.commit()
        }
    }

    private val migrations = mapOf(
        1 to """
            CREATE TABLE IF NOT EXISTS users (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              google_subject TEXT NOT NULL UNIQUE,
              email TEXT,
              display_name TEXT,
              profile_image_url TEXT,
              created_at TEXT NOT NULL,
              updated_at TEXT NOT NULL,
              last_login_at TEXT NOT NULL
            )
            -- statement
            CREATE TABLE IF NOT EXISTS games (
              id TEXT PRIMARY KEY,
              white_user_id INTEGER REFERENCES users(id),
              black_user_id INTEGER REFERENCES users(id),
              status TEXT NOT NULL,
              result TEXT NOT NULL,
              end_reason TEXT,
              current_turn TEXT NOT NULL,
              state_version INTEGER NOT NULL,
              white_in_check INTEGER NOT NULL,
              black_in_check INTEGER NOT NULL,
              state_json TEXT NOT NULL,
              draw_offer_by_user_id INTEGER REFERENCES users(id),
              created_at TEXT NOT NULL,
              started_at TEXT,
              finished_at TEXT,
              final_move_count INTEGER NOT NULL DEFAULT 0
            )
            -- statement
            CREATE TABLE IF NOT EXISTS friendly_rooms (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              invite_code TEXT NOT NULL UNIQUE,
              host_user_id INTEGER NOT NULL REFERENCES users(id),
              guest_user_id INTEGER REFERENCES users(id),
              game_id TEXT NOT NULL REFERENCES games(id),
              status TEXT NOT NULL,
              created_at TEXT NOT NULL,
              joined_at TEXT,
              finished_at TEXT,
              expires_at TEXT,
              CHECK (guest_user_id IS NULL OR guest_user_id <> host_user_id)
            )
            -- statement
            CREATE TABLE IF NOT EXISTS moves (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              game_id TEXT NOT NULL REFERENCES games(id),
              sequence_number INTEGER NOT NULL,
              piece_id TEXT NOT NULL,
              piece_type TEXT NOT NULL,
              piece_color TEXT NOT NULL,
              start_x_expression TEXT NOT NULL,
              start_y_expression TEXT NOT NULL,
              start_x_value REAL NOT NULL,
              start_y_value REAL NOT NULL,
              end_x_expression TEXT NOT NULL,
              end_y_expression TEXT NOT NULL,
              end_x_value REAL NOT NULL,
              end_y_value REAL NOT NULL,
              captured_piece_id TEXT,
              created_at TEXT NOT NULL,
              UNIQUE(game_id, sequence_number)
            )
            -- statement
            CREATE INDEX IF NOT EXISTS idx_rooms_invite_code ON friendly_rooms(invite_code)
            -- statement
            CREATE INDEX IF NOT EXISTS idx_games_players ON games(white_user_id, black_user_id)
            -- statement
            CREATE INDEX IF NOT EXISTS idx_moves_game_sequence ON moves(game_id, sequence_number)
        """.trimIndent(),
        2 to """
            ALTER TABLE moves ADD COLUMN special_move TEXT
        """.trimIndent(),
        3 to """
            ALTER TABLE users ADD COLUMN role TEXT NOT NULL DEFAULT 'USER'
            -- statement
            ALTER TABLE users ADD COLUMN banned_at TEXT
        """.trimIndent(),
    )
}
