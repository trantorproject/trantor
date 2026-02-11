package dev.botta.trantor.core.broadcast

import dev.botta.cqbus.identity.Identity
import dev.botta.trantor.primitives.events.Event

class NullBroadcaster: Broadcaster {
    override fun send(channel: String, event: Event) {
    }

    override fun register(channel: Channel) {
    }

    override fun getSubscribers(channel: String) = listOf<ClientSession>()

    override fun getSessions(identity: Identity) = listOf<ClientSession>()
}
