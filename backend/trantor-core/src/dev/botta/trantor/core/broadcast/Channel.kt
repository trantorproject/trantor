package dev.botta.trantor.core.broadcast

typealias ChannelParams = Map<String, String>

abstract class Channel(val path: String) {
    open fun authorize(params: ChannelParams, session: ClientSession): Boolean {
        return true
    }

    open fun onJoin(params: ChannelParams, session: ClientSession) {}

    open fun onLeave(params: ChannelParams, session: ClientSession) {}
}
