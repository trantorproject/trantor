package dev.botta.trantor.domain.events

import dev.botta.trantor.core.events.AppEvent
import java.util.*

abstract class DomainEvent(id: UUID = UUID.randomUUID()): AppEvent(id)
