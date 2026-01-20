package dev.botta.trantor.events

import java.util.*

abstract class DomainEvent(id: UUID = UUID.randomUUID()): AppEvent(id)
