package dev.botta.trantor.domain.repositories

import dev.botta.trantor.domain.*

interface Repository<ID: Id, T: Aggregate<ID>> {
    fun get(id: ID): T
    fun getAll(): List<T>
    fun add(vararg entities: T)
    fun update(vararg entities: T)
    fun remove(vararg ids: ID)
}
