package dev.botta.trantor.core.events

import dev.botta.trantor.primitives.events.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass

class DefaultEventDispatcherTest {
    @Test
    fun `publish notifies a handler subscribed to the event type`() {
        var handledEvent: Event? = null
        subscribe<MyEvent> { handledEvent = it }
        val event = MyEvent()

        dispatcher.publish(event)

        assert(handledEvent == event)
    }

    @Test
    fun `publish notifies all handlers subscribed to the event type`() {
        var myEventHandler1Called = false
        var myEventHandler2Called = false
        subscribe<MyEvent> { myEventHandler1Called = true }
        subscribe<MyEvent> { myEventHandler2Called = true }

        dispatcher.publish(MyEvent())

        assert(myEventHandler1Called)
        assert(myEventHandler2Called)
    }

    @Test
    fun `publish doesn't notify handlers not subscribed to event type`() {
        var otherEventHandlerCalled = false
        subscribe<OtherEvent> { otherEventHandlerCalled = true }

        dispatcher.publish(MyEvent())

        assertThat(otherEventHandlerCalled).isFalse
    }

    @Test
    fun `publish notifies a handler subscribed to an event base type`() {
        var myEventHandlerCalled = false
        subscribe<MyEventBase> { myEventHandlerCalled = true }

        dispatcher.publish(MyEvent())

        assert(myEventHandlerCalled)
    }

    @Test
    fun `a handler can subscribe to multiple event types`() {
        val handledEvents = mutableListOf<Event>()
        subscribe(listOf(MyEvent::class, OtherEvent::class)) { handledEvents.add(it) }

        dispatcher.publish(listOf(MyEvent(), OtherEvent()))

        assertThat(handledEvents.size).isEqualTo(2)
    }

    @Test
    fun `anonymous event listener`() {
        var handledEvent: Event? = null
        dispatcher.on<MyEvent> {
            handledEvent = it
        }
        val event = MyEvent()

        dispatcher.publish(event)

        assert(handledEvent == event)
    }

    private fun subscribe(eventTypes: List<KClass<*>>, onEventFunc: (event: Event) -> Unit) {
        dispatcher.subscribe(SimpleEventHandler(eventTypes, onEventFunc))
    }

    private inline fun <reified T> subscribe(noinline onEventFunc: (event: Event) -> Unit) {
        dispatcher.subscribe(SimpleEventHandler(T::class, onEventFunc))
    }

    private val dispatcher = DefaultEventDispatcher()

    abstract class MyEventBase: Event()
    class MyEvent: MyEventBase()
    class OtherEvent: Event()

    class SimpleEventHandler(
        override val eventTypes: List<KClass<*>>,
        private val onEventFunc: (event: Event) -> Unit,
    ): EventHandler {
        constructor(eventType: KClass<*>, onEventFunc: (event: Event) -> Unit): this(listOf(eventType), onEventFunc)

        override fun on(event: Event) {
            onEventFunc(event)
        }
    }
}
