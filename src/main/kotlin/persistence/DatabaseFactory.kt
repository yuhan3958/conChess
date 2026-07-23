package com.yuhan8954.persistence

import java.sql.Connection
import java.sql.DriverManager

/** Creates SQLite connections and applies startup database settings. */
class DatabaseFactory(private val databasePath: String) {
    init {
        Class.forName("org.sqlite.JDBC")
    }

    fun connection(): Connection {
        val connection = DriverManager.getConnection("jdbc:sqlite:$databasePath")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute("PRAGMA journal_mode = WAL")
        }
        return connection
    }
}
