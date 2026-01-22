package dev.botta.trantor.core.events

import dev.botta.trantor.core.tx.TransactionCallback
import dev.botta.trantor.core.tx.TransactionManager

class TransactionAwareAppEventPublisher(private val publisher: AppEventPublisher, private val transactionManager: TransactionManager):
    AppEventPublisher {
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
