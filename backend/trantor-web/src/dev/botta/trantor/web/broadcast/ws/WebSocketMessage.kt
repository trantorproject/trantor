package dev.botta.trantor.web.broadcast.ws

import java.util.*

abstract class WebSocketMessage(val type: String) {
    val id: UUID = UUID.randomUUID()
}
