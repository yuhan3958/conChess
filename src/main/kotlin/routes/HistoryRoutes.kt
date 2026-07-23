package com.yuhan8954.routes

import com.yuhan8954.auth.CurrentUserProvider
import com.yuhan8954.persistence.sqlite.SqliteStore
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Registers current-user match history routes. */
fun Route.historyRoutes(currentUser: CurrentUserProvider, store: SqliteStore) {
    get("/api/me/stats") {
        val user = currentUser.requireUser(call)
        call.respond(store.statsFor(user.userId))
    }

    get("/api/me/matches") {
        val user = currentUser.requireUser(call)
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
        val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 20
        call.respond(store.matchesFor(user.userId, page, size))
    }
}
