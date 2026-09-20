@file:Suppress("ClassName")

package dev.botta.trantor.core.jobs

import dev.botta.trantor.core.jobs.serialization.DefaultJobSerializer
import dev.botta.trantor.core.queues.*
import dev.botta.trantor.core.tx.NullTransactionManager
import dev.botta.trantor.core.tx.Transaction
import dev.botta.trantor.core.tx.TransactionManager
import dev.botta.trantor.serialization.gson.GsonSerializer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.MDC

class DefaultJobDispatcherTest {
    @Nested
    inner class `dispatching outside a transaction` {
        @Test
        fun `puts the job on the queue right away`() {
            dispatcher().dispatch(SendEmail("nico@example.com"))

            assertThat(emails.enqueued).hasSize(1)
        }

        @Test
        fun `what travels is the job type and its contents`() {
            dispatcher().dispatch(SendEmail("nico@example.com"))

            val message = emails.enqueued.first().first
            assertThat(message.type).isEqualTo("SendEmail")
            assertThat(message.body).contains("nico@example.com")
        }

        @Test
        fun `the correlation id goes with it, so the job logs under the same trace`() {
            MDC.put("cid", "abc123")

            dispatcher().dispatch(SendEmail())

            assertThat(emails.enqueued.first().first.cid).isEqualTo("abc123")
        }

        @Test
        fun `without a correlation id it travels without one`() {
            dispatcher().dispatch(SendEmail())

            assertThat(emails.enqueued.first().first.cid).isNull()
        }

        @Test
        fun `the options go through, so a delayed job stays delayed`() {
            dispatcher().dispatch(SendEmail(), options = EnqueueOptions(delaySeconds = 60))

            assertThat(emails.enqueued.first().second.delaySeconds).isEqualTo(60)
        }

        @Test
        fun `a named queue takes it instead of the default`() {
            dispatcher().dispatch(SendEmail(), queueName = "reports")

            assertThat(reports.enqueued).hasSize(1)
            assertThat(emails.enqueued).isEmpty()
        }

        @Test
        fun `a queue nobody registered says so instead of losing the job`() {
            assertThatThrownBy { dispatcher().dispatch(SendEmail(), queueName = "nowhere") }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("nowhere")
        }
    }

    @Nested
    inner class `dispatching inside a transaction` {
        @Test
        fun `waits for the commit, so a rolled back change produces no work`() {
            val transactions = FakeTransactionManager().apply { begin() }

            dispatcher(transactions).dispatch(SendEmail())

            assertThat(emails.enqueued).isEmpty()
        }

        @Test
        fun `and goes out once it commits`() {
            val transactions = FakeTransactionManager().apply { begin() }
            dispatcher(transactions).dispatch(SendEmail())

            transactions.commit()

            assertThat(emails.enqueued).hasSize(1)
        }

        @Test
        fun `a rollback leaves the queue empty`() {
            val transactions = FakeTransactionManager().apply { begin() }
            dispatcher(transactions).dispatch(SendEmail())

            transactions.rollback()

            assertThat(emails.enqueued).isEmpty()
        }

        @Test
        fun `an application that says not to wait gets it right away`() {
            val transactions = FakeTransactionManager().apply { begin() }

            dispatcher(transactions, afterCommit = false).dispatch(SendEmail())

            assertThat(emails.enqueued).hasSize(1)
        }
    }

    @Nested
    inner class `registering a handler` {
        @Test
        fun `makes the job known to the serializer, or it could not be read back`() {
            dispatcher().registerHandler(SendEmail::class, SendEmailHandler())

            assertThat(serializer.isRegistered(SendEmail::class)).isTrue()
        }

        @Test
        fun `and puts it in the handler registry`() {
            val handler = SendEmailHandler()

            dispatcher().registerHandler(SendEmail::class, handler)

            assertThat(handlers.getHandler(SendEmail::class)).isSameAs(handler)
        }
    }

    @AfterEach
    fun leaveTheThreadAsItWasFound() {
        MDC.clear()
    }

    private fun dispatcher(
        transactions: TransactionManager = NullTransactionManager(),
        afterCommit: Boolean = true,
    ) = DefaultJobDispatcher(queues, handlers, serializer, transactions, afterCommit)

    private class SendEmail(val to: String = ""): Job()

    private class SendEmailHandler: JobHandler<SendEmail> {
        override fun execute(job: SendEmail) {}
    }

    private class RecordingQueue(override val name: String): MessageQueue {
        val enqueued = mutableListOf<Pair<Message, EnqueueOptions>>()

        override fun enqueue(message: Message, options: EnqueueOptions) {
            enqueued.add(message to options)
        }

        override fun poll() = emptyList<ReceivedMessage>()

        override fun clear() = enqueued.clear()

        override fun size() = enqueued.size

        override fun delete(message: ReceivedMessage) {}
    }

    /** Holds one transaction and runs its callbacks, which is all the dispatcher asks of it. */
    private class FakeTransactionManager: TransactionManager {
        override var activeTransaction: Transaction? = null

        fun begin() {
            activeTransaction = beginTransaction()
        }

        fun commit() {
            (activeTransaction as FakeTransaction).also { activeTransaction = null }.commit()
        }

        fun rollback() {
            (activeTransaction as FakeTransaction).also { activeTransaction = null }.rollback()
        }

        override fun beginTransaction(): Transaction = FakeTransaction()
    }

    private class FakeTransaction: Transaction {
        override var isClosed = false

        private val onCommit = mutableListOf<() -> Unit>()
        private val onRollback = mutableListOf<() -> Unit>()

        override fun commit() = onCommit.forEach { it() }

        override fun rollback() = onRollback.forEach { it() }

        override fun afterComplete(action: () -> Unit) {
            onCommit.add(action)
            onRollback.add(action)
        }

        override fun afterCommit(action: () -> Unit) {
            onCommit.add(action)
        }

        override fun afterRollback(action: () -> Unit) {
            onRollback.add(action)
        }

        override fun close() {
            isClosed = true
        }
    }

    private val emails = RecordingQueue("emails")
    private val reports = RecordingQueue("reports")
    private val queues = JobQueueRegistry().apply {
        addQueue("emails", emails)
        addQueue("reports", reports)
    }
    private val handlers = JobHandlerRegistry()
    private val serializer = DefaultJobSerializer(GsonSerializer())
}
