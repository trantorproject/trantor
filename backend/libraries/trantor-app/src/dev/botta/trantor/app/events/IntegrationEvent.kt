package dev.botta.trantor.app.events

import dev.botta.trantor.core.events.AppEvent
import java.util.UUID

abstract class IntegrationEvent(id: UUID = UUID.randomUUID()): AppEvent(id)
