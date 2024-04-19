package dev.botta.trantor.data.jdbc

import java.sql.Savepoint

class SavePoints {
    var active = mutableListOf<Savepoint>()
    var released = mutableListOf<Savepoint>()
    var rollbacked = mutableListOf<Savepoint>()
}
