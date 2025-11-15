package dev.botta.trantor.domain.repositories

import dev.botta.trantor.domain.*

interface Repository<ID: Id, T: Aggregate<ID>> {
    suspend fun get(id: ID): T
    suspend fun getAll(): List<T>
    suspend fun add(vararg entities: T)
    suspend fun update(vararg entities: T)
    suspend fun remove(vararg ids: ID)
}
