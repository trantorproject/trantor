package dev.botta.trantor.tx.events

import dev.botta.trantor.core.Event
import dev.botta.trantor.eventBus.*
import dev.botta.trantor.tx.*

class TransactionAwareEventBus(private val eventBus: EventBus, private val transactionManager: TransactionManager): EventBus {
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

    override fun subscribe(handler: EventHandler) {
        eventBus.subscribe(handler)
    }

    override fun start() {
        eventBus.start()
    }

    override fun stop() {
        eventBus.stop()
    }
}
