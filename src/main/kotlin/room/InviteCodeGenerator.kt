package com.yuhan8954.room

import java.security.SecureRandom
import java.util.Base64

/** Generates unguessable URL-safe invite codes. */
class InviteCodeGenerator {
    private val random = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(18)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
