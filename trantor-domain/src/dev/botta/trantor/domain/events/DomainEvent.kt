package dev.botta.trantor.domain.events

import dev.botta.trantor.primitives.events.Event
import java.util.*

abstract class DomainEvent(id: UUID = UUID.randomUUID()): Event(id)
