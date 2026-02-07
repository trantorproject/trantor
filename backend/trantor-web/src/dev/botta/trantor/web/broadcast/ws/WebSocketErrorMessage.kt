package dev.botta.trantor.web.broadcast.ws

data class WebSocketErrorMessage(val message: String): WebSocketMessage("error")
