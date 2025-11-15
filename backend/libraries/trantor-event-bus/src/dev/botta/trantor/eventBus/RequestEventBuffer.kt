package dev.botta.trantor.eventBus

import dev.botta.trantor.core.Event
import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.CoroutineContext

class RequestEventBuffer(
    val pending: MutableList<Event> = mutableListOf()
) : ThreadContextElement<RequestEventBuffer?> {

    companion object Key : CoroutineContext.Key<RequestEventBuffer>

    override val key: CoroutineContext.Key<*> = Key

    override fun updateThreadContext(context: CoroutineContext) = this

    override fun restoreThreadContext(context: CoroutineContext, oldState: RequestEventBuffer?) {}
}
