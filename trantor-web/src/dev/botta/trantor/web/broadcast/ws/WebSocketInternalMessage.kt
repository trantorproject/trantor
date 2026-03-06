package dev.botta.trantor.web.broadcast.ws

class WebSocketInternalMessage(type: String, val data: Map<String, Any> = mapOf()): WebSocketMessage(type)
