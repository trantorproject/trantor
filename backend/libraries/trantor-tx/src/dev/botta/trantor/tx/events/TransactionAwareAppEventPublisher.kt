package dev.botta.trantor.tx.events

import dev.botta.trantor.core.events.*
import dev.botta.trantor.eventBus.EventBus
import dev.botta.trantor.tx.*

class TransactionAwareAppEventPublisher(private val eventBus: EventBus, private val transactionManager: TransactionManager): AppEventPublisher {
    override fun publish(events: List<Event>) {
        events.forEach { publish(it) }
    }

    override fun publish(event: Event) {
        if (!transactionManager.hasActiveTransaction()) {
            eventBus.publish(event)
            return
        }

        transactionManager.registerActiveTransactionCallback(object: TransactionCallback {
            override fun onCommit() {
                eventBus.publish(event)
            }
            override fun onRollback() {
            }
        })
    }
}
