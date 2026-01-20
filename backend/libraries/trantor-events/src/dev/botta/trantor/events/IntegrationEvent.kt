package dev.botta.trantor.events

import java.util.*

abstract class IntegrationEvent(id: UUID = UUID.randomUUID()): AppEvent(id)
