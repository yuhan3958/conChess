package com.yuhan8954.application

import java.nio.file.Files
import java.nio.file.Path

/** Externalized application settings. */
data class AppConfig(
    val googleClientId: String,
    val googleClientSecret: String,
    val googleRedirectUri: String,
    val sessionSecret: String,
    val databasePath: String,
    val appBaseUrl: String,
    val environment: String,
    val profileImageStoragePath: String,
) {
    val secureCookies: Boolean = environment.equals("production", ignoreCase = true)

    companion object {
        private val dotEnv: Map<String, String> by lazy { loadDotEnv() }

        fun fromEnvironment(): AppConfig = AppConfig(
            googleClientId = env("GOOGLE_CLIENT_ID", ""),
            googleClientSecret = env("GOOGLE_CLIENT_SECRET", ""),
            googleRedirectUri = env("GOOGLE_REDIRECT_URI", "http://localhost:8080/auth/google/callback"),
            sessionSecret = env("SESSION_SECRET", "dev-session-secret-change-me-32-bytes"),
            databasePath = env("DATABASE_PATH", "identifier.sqlite"),
            appBaseUrl = env("APP_BASE_URL", "http://localhost:8080"),
            environment = env("ENVIRONMENT", "development"),
            profileImageStoragePath = env("PROFILE_IMAGE_STORAGE_PATH", "uploads/profile-images"),
        )

        private fun env(name: String, default: String): String =
            System.getenv(name)?.takeIf { it.isNotBlank() }
                ?: dotEnv[name]?.takeIf { it.isNotBlank() }
                ?: default

        private fun loadDotEnv(): Map<String, String> {
            val path = Path.of(".env")
            if (!Files.isRegularFile(path)) {
                return emptyMap()
            }

            return Files.readAllLines(path)
                .mapNotNull(::parseDotEnvLine)
                .toMap()
        }

        private fun parseDotEnvLine(line: String): Pair<String, String>? {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                return null
            }

            val assignment = trimmed.removePrefix("export ").trimStart()
            val separatorIndex = assignment.indexOf('=')
            if (separatorIndex <= 0) {
                return null
            }

            val key = assignment.substring(0, separatorIndex).trim()
            if (key.isEmpty()) {
                return null
            }

            val rawValue = assignment.substring(separatorIndex + 1).trim()
            val value = when {
                rawValue.length >= 2 && rawValue.first() == '"' && rawValue.last() == '"' ->
                    rawValue.substring(1, rawValue.lastIndex)

                rawValue.length >= 2 && rawValue.first() == '\'' && rawValue.last() == '\'' ->
                    rawValue.substring(1, rawValue.lastIndex)

                else -> rawValue.substringBefore(" #").trimEnd()
            }

            return key to value
        }
    }
}
