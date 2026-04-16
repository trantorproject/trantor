package dev.botta.trantor.domain.repositories

import dev.botta.trantor.domain.*

interface Repository<ID: Id, T: Aggregate<ID>> {
    fun get(id: ID): T
    fun getAll(): List<T>
    fun add(vararg entities: T)
    fun update(vararg entities: T)
    fun remove(vararg ids: ID)
}

inline fun <ID: Id, reified T: Aggregate<ID>> Repository<ID, T>.add(entities: List<T>) {
    add(*entities.toTypedArray())
}

inline fun <ID: Id, reified T: Aggregate<ID>> Repository<ID, T>.update(entities: List<T>) {
    update(*entities.toTypedArray())
}

inline fun <reified ID: Id, T: Aggregate<ID>> Repository<ID, T>.remove(ids: List<ID>) {
    remove(*ids.toTypedArray())
}
