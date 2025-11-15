package dev.botta.trantor.data.coroutines

import kotlinx.coroutines.CoroutineDispatcher

interface DbDispatcherProvider {
    fun get(): CoroutineDispatcher
}
