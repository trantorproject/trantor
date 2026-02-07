package dev.botta.trantor.web.broadcast.ws

import dev.botta.trantor.primitives.events.Event

data class WebSocketEventMessage(val channel: String, val event: Event): WebSocketMessage("event")
