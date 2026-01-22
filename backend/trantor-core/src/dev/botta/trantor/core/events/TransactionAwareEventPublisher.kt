package dev.botta.trantor.core.events

import dev.botta.trantor.core.tx.*
import dev.botta.trantor.primitives.events.Event

class TransactionAwareEventPublisher(
    private val publisher: EventPublisher,
    private val transactionManager: TransactionManager,
): EventPublisher {
    override fun publish(events: List<Event>) {
        events.forEach { publish(it) }
    }

    override fun publish(event: Event) {
        if (!transactionManager.hasActiveTransaction()) {
            publisher.publish(event)
            return
        }

        transactionManager.registerActiveTransactionCallback(object: TransactionCallback {
            override fun onCommit() {
                publisher.publish(event)
            }

            override fun onRollback() {
            }
        })
    }
}
