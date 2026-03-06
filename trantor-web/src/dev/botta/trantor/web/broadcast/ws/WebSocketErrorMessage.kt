package dev.botta.trantor.web.broadcast.ws

data class WebSocketErrorMessage(val code: String, val message: String, val data: Map<String, Any> = mapOf()): WebSocketMessage("error")
