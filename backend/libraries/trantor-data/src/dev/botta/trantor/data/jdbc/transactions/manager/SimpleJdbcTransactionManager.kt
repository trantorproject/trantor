package dev.botta.trantor.data.jdbc.transactions.manager

import dev.botta.trantor.data.jdbc.DataSource
import dev.botta.trantor.data.jdbc.transactions.JdbcTransaction

class SimpleJdbcTransactionManager(dataSource: DataSource): JdbcTransactionManager(dataSource) {
    override var activeTransaction: JdbcTransaction? = null
}
