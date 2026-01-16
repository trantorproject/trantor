package dev.botta.trantor.core.events

import java.util.*

abstract class AppEvent(id: UUID = UUID.randomUUID()): Event(id)
