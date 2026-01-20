package dev.botta.trantor.events

import java.util.*

abstract class AppEvent(id: UUID = UUID.randomUUID()): Event(id)
