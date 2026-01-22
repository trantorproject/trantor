package dev.botta.trantor.data.jooq

import dev.botta.trantor.core.tx.Transaction
import org.jooq.Transaction as JooqTransaction

class TrantorJooqTransaction(val inner: Transaction): JooqTransaction
