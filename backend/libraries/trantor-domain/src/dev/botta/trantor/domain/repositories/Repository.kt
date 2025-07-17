package dev.botta.trantor.domain.repositories

import dev.botta.trantor.domain.*

interface Repository<T: Aggregate<T>> {
    fun get(id: Id<T>): T
    fun getAll(): List<T>
    fun add(vararg entities: T)
    fun update(vararg entities: T)
    fun remove(vararg ids: Id<T>)
}
