package dev.botta.trantor.core.broadcast

import dev.botta.cqbus.identity.Identity

typealias ChannelParams = Map<String, String>

abstract class Channel(val path: String) {
    fun authorize(params: ChannelParams, identity: Identity): Boolean {
        return true
    }

    open fun onJoin(params: ChannelParams, session: ClientSession) {}

    open fun onLeave(params: ChannelParams, session: ClientSession) {}
}
