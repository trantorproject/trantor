package dev.botta.trantor.core.broadcast

import dev.botta.cqbus.identity.Identity
import dev.botta.trantor.primitives.events.Event

interface Broadcaster {
    fun send(channel: String, event: Event)
    fun register(channel: Channel)
    fun getSubscribers(channel: String): List<ClientSession>
    fun getSessions(identity: Identity): List<ClientSession>
}
