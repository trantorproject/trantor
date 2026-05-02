package dev.botta.trantor.domain.events

import com.github.f4b6a3.uuid.UuidCreator
import dev.botta.trantor.primitives.events.Event
import java.util.*

abstract class DomainEvent(id: UUID = UuidCreator.getTimeOrderedEpoch()): Event(id)
