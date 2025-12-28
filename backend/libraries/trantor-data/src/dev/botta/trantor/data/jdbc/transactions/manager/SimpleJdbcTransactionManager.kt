package dev.botta.trantor.data.jdbc.transactions.manager

import dev.botta.trantor.data.jdbc.transactions.*

class SimpleJdbcTransactionManager(dataSource: TransactionAwareDataSource): JdbcTransactionManager(dataSource) {
    override var activeTransaction: JdbcTransaction? = null
}
