package com.yuhan8954.routes

import com.yuhan8954.auth.AuthException
import com.yuhan8954.auth.UserSession
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions

/** Verifies the per-session CSRF token for state-changing HTTP requests. */
fun ApplicationCall.requireCsrf() {
    val session = sessions.get<UserSession>()
    val header = request.headers["X-CSRF-Token"]
    if (session == null || header == null || header != session.csrfToken) {
        throw AuthException(HttpStatusCode.Forbidden, "CSRF_TOKEN_INVALID", "Invalid CSRF token.")
    }
}
