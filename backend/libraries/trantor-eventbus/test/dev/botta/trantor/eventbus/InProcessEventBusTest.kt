package dev.botta.trantor.eventbus

import dev.botta.trantor.core.events.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass

class InProcessEventBusTest {
    @Test
    fun `publish notifies a handler subscribed to the event type`() {
        var myEventHandlerCalled = false
        subscribe<MyEvent> { myEventHandlerCalled = true }

        eventBus.publish(MyEvent())

        assert(myEventHandlerCalled)
    }

    @Test
    fun `publish notifies all handlers subscribed to the event type`() {
        var myEventHandler1Called = false
        var myEventHandler2Called = false
        subscribe<MyEvent> { myEventHandler1Called = true }
        subscribe<MyEvent> { myEventHandler2Called = true }

        eventBus.publish(MyEvent())

        assert(myEventHandler1Called)
        assert(myEventHandler2Called)
    }

    @Test
    fun `publish doesn't notify handlers not subscribed to event type`() {
        var otherEventHandlerCalled = false
        subscribe<OtherEvent> { otherEventHandlerCalled = true }

        eventBus.publish(MyEvent())

        assertThat(otherEventHandlerCalled).isFalse
    }

    @Test
    fun `publishing many events of same type notifies all events to a handler subscribed to the event type`() {
        val handledEvents = mutableListOf<Event>()
        subscribe<MyEvent> { handledEvents.add(it) }

        eventBus.publish(listOf(MyEvent(), MyEvent()))

        assertThat(handledEvents.size).isEqualTo(2)
    }

    @Test
    fun `publishing many events of different types notifies only subscribed events to handler`() {
        val handledEvents = mutableListOf<Event>()
        subscribe<MyEvent> { handledEvents.add(it) }

        eventBus.publish(listOf(MyEvent(), OtherEvent()))

        assertThat(handledEvents.single()).isInstanceOf(MyEvent::class.java)
    }

    @Test
    fun `publishing many events notifies all handlers subscribed to each event types`() {
        val myEventHandlerEvents = mutableListOf<Event>()
        val otherEventHandlerEvents = mutableListOf<Event>()
        subscribe<MyEvent> { myEventHandlerEvents.add(it) }
        subscribe<OtherEvent> { otherEventHandlerEvents.add(it) }

        eventBus.publish(listOf(MyEvent(), OtherEvent()))

        assertThat(myEventHandlerEvents.single()).isInstanceOf(MyEvent::class.java)
        assertThat(otherEventHandlerEvents.single()).isInstanceOf(OtherEvent::class.java)
    }

    @Test
    fun `publish notifies a handler subscribed to an event base type`() {
        var myEventHandlerCalled = false
        subscribe<MyEventBase> { myEventHandlerCalled = true }

        eventBus.publish(MyEvent())

        assert(myEventHandlerCalled)
    }

    @Test
    fun `a handler can subscribe to multiple event types`() {
        val handledEvents = mutableListOf<Event>()
        subscribe(listOf(MyEvent::class, OtherEvent::class)) { handledEvents.add(it) }

        eventBus.publish(listOf(MyEvent(), OtherEvent()))

        assertThat(handledEvents.size).isEqualTo(2)
    }

    private fun subscribe(eventTypes: List<KClass<*>>, onEventFunc: (event: Event) -> Unit) {
        eventBus.subscribe(SimpleEventHandler(eventTypes, onEventFunc))
    }

    private inline fun <reified T> subscribe(noinline onEventFunc: (event: Event) -> Unit) {
        eventBus.subscribe(SimpleEventHandler(T::class, onEventFunc))
    }

    private fun createThread(name: String = "OtherThread", runnable: Runnable): Thread {
        return Thread(runnable, name)
    }

    private fun startAndWait(otherThread: Thread) {
        otherThread.start()
        otherThread.join()
    }

    private val eventBus = InProcessEventBus()

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
