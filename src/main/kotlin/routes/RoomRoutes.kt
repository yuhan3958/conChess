package com.yuhan8954.routes

import com.yuhan8954.auth.CurrentUserProvider
import com.yuhan8954.common.ApiError
import com.yuhan8954.room.FriendlyRoomService
import com.yuhan8954.room.JoinRoomResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** Registers friendly room API routes. */
fun Route.roomRoutes(currentUser: CurrentUserProvider, rooms: FriendlyRoomService) {
    post("/api/rooms") {
        call.requireCsrf()
        val user = currentUser.requireUser(call)
        call.respond(HttpStatusCode.Created, rooms.createRoom(user.userId))
    }

    get("/api/rooms/{inviteCode}") {
        currentUser.requireUser(call)
        val invite = call.parameters["inviteCode"].orEmpty()
        val room = rooms.getRoom(invite)
        if (room == null) call.respond(HttpStatusCode.NotFound, ApiError("ROOM_NOT_FOUND", "Room was not found."))
        else call.respond(room)
    }

    post("/api/rooms/{inviteCode}/join") {
        call.requireCsrf()
        val user = currentUser.requireUser(call)
        when (val result = rooms.join(call.parameters["inviteCode"].orEmpty(), user.userId)) {
            is JoinRoomResult.Success -> call.respond(result)
            is JoinRoomResult.Failure -> call.respond(HttpStatusCode.Conflict, ApiError(result.code, result.message))
        }
    }

    post("/api/rooms/{inviteCode}/cancel") {
        call.requireCsrf()
        val user = currentUser.requireUser(call)
        if (rooms.cancel(call.parameters["inviteCode"].orEmpty(), user.userId)) call.respond(mapOf("ok" to true))
        else call.respond(HttpStatusCode.Conflict, ApiError("ROOM_CANCEL_FAILED", "Room could not be cancelled."))
    }
}
