@file:Suppress("ClassName")

package dev.botta.trantor.core.events

import com.google.gson.JsonParseException
import dev.botta.trantor.core.events.serialization.DefaultEventSerializer
import dev.botta.trantor.core.jobs.*
import dev.botta.trantor.core.queues.EnqueueOptions
import dev.botta.trantor.core.tx.NullTransactionManager
import dev.botta.trantor.primitives.events.*
import dev.botta.trantor.primitives.events.serialization.EventClassNotFound
import dev.botta.trantor.serialization.gson.GsonSerializer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
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

    @Test
    fun `don't publish immediately if running inside a defer block`() {
        var myEventHandlerCalled = false
        subscribe<MyEvent> { myEventHandlerCalled = true }
        dispatcher.defer {
            dispatcher.publish(MyEvent())
            assertThat(myEventHandlerCalled).isFalse
        }
    }

    @Test
    fun `publish events generated inside a defer block when it finishes`() {
        var myEventHandlerCalled = false
        subscribe<MyEvent> { myEventHandlerCalled = true }

        dispatcher.defer {
            dispatcher.publish(MyEvent())
        }

        assertThat(myEventHandlerCalled).isTrue
    }

    @Test
    fun `defer is thread local - events in another thread are published immediately`() {
        var handlerCalledInOtherThread = false
        subscribe<MyEvent> {
            if (Thread.currentThread().name == "OtherThread") {
                handlerCalledInOtherThread = true
            }
        }

        dispatcher.defer {
            val otherThread = createThread {
                dispatcher.publish(MyEvent())
            }

            startAndWait(otherThread)

            assertThat(handlerCalledInOtherThread).isTrue
        }
    }

    @Nested
    inner class `a handler in line that fails` {
        @Test
        fun `stops neither the other handlers nor whoever published`() {
            var otherHandlerCalled = false
            subscribe<MyEvent> { error("the server is down") }
            subscribe<MyEvent> { otherHandlerCalled = true }

            dispatcher.publish(MyEvent())

            assertThat(otherHandlerCalled).isTrue
        }
    }

    @Nested
    inner class `a queued handler` {
        @Test
        fun `runs when its job does, and not in line`() {
            val handled = mutableListOf<Event>()
            dispatcher.subscribe(SyncInventory { handled.add(it) })
            val event = MyEvent()

            dispatcher.publish(event)

            assertThat(handled).isEmpty()

            jobs.runDispatched()

            assertThat(handled).containsExactly(event)
        }

        @Test
        fun `that fails makes its job fail, so the queue retries it`() {
            dispatcher.subscribe(SyncInventory { error("the server is down") })
            dispatcher.publish(MyEvent())

            assertThatThrownBy { jobs.runDispatched() }.hasMessage("the server is down")
        }

        @Test
        fun `that is not registered here makes its job fail, because it may be a deploy in progress`() {
            subscribe<MyEvent> { }

            assertThatThrownBy { jobs.run(ProcessEventHandlerJob("SyncInventory", "MyEvent", "{}")) }
                .isInstanceOf(EventHandlerNotRegisteredError::class.java)
                .hasFieldOrPropertyWithValue("handlerType", "SyncInventory")
                .hasFieldOrPropertyWithValue("eventType", "MyEvent")
        }

        @Test
        fun `that does not take the event of its job makes it fail too`() {
            dispatcher.subscribe(SyncInventory { })
            subscribe<OtherEvent> { }

            assertThatThrownBy { jobs.run(ProcessEventHandlerJob("SyncInventory", "OtherEvent", "{}")) }
                .isInstanceOf(EventHandlerNotRegisteredError::class.java)
        }

        @Test
        fun `whose event is of a type nobody here knows makes its job fail`() {
            dispatcher.subscribe(SyncInventory { })

            assertThatThrownBy { jobs.run(ProcessEventHandlerJob("SyncInventory", "EventOfTheNextRelease", "{}")) }
                .isInstanceOf(EventClassNotFound::class.java)
        }

        @Test
        fun `whose event does not parse makes its job fail, rather than vanish`() {
            dispatcher.subscribe(SyncInventory { })

            assertThatThrownBy { jobs.run(ProcessEventHandlerJob("SyncInventory", "MyEvent", """{"id":{"not":"a uuid"}}""")) }
                .isInstanceOf(JsonParseException::class.java)
        }
    }

    private fun subscribe(eventTypes: List<KClass<out Event>>, onEventFunc: (event: Event) -> Unit) {
        dispatcher.subscribe(SimpleEventHandler(eventTypes, onEventFunc))
    }

    private inline fun <reified T: Event> subscribe(noinline onEventFunc: (event: Event) -> Unit) {
        dispatcher.subscribe(SimpleEventHandler(T::class, onEventFunc))
    }

    private fun createThread(name: String = "OtherThread", runnable: Runnable): Thread {
        return Thread(runnable, name)
    }

    private fun startAndWait(otherThread: Thread) {
        otherThread.start()
        otherThread.join()
    }

    private val jobs = RunningJobDispatcher()
    private val dispatcher = DefaultEventDispatcher(
        NullTransactionManager(),
        jobs,
        DefaultEventSerializer(GsonSerializer()),
    )

    abstract class MyEventBase: Event()
    class MyEvent: MyEventBase()
    class OtherEvent: Event()

    class SimpleEventHandler(
        override val eventTypes: List<KClass<out Event>>,
        private val onEventFunc: (event: Event) -> Unit,
    ): EventHandler {
        constructor(eventType: KClass<out Event>, onEventFunc: (event: Event) -> Unit): this(listOf(eventType), onEventFunc)

        override fun on(event: Event) {
            onEventFunc(event)
        }
    }

    class SyncInventory(private val onEventFunc: (event: Event) -> Unit): QueuedEventHandler() {
        override val eventTypes = listOf(MyEvent::class)

        override fun on(event: Event) {
            onEventFunc(event)
        }
    }

    /** Keeps what is dispatched and runs it when the test says, as a job processor would on another thread. */
    private class RunningJobDispatcher: JobDispatcher {
        private val handlers = mutableMapOf<KClass<*>, JobHandler<*>>()
        private val dispatched = mutableListOf<Job>()

        override fun dispatch(job: Job, queueName: String?, options: EnqueueOptions) {
            dispatched.add(job)
        }

        override fun <T: Job> registerHandler(jobType: KClass<T>, handler: JobHandler<T>) {
            handlers[jobType] = handler
        }

        fun runDispatched() {
            dispatched.toList().also { dispatched.clear() }.forEach { run(it) }
        }

        @Suppress("UNCHECKED_CAST")
        fun run(job: Job) {
            (handlers.getValue(job::class) as JobHandler<Job>).execute(job)
        }
    }
}
