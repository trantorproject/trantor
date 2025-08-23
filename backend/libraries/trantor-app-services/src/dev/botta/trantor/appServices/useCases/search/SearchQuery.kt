package dev.botta.trantor.appServices.useCases.search

import dev.botta.cqbus.requests.Query
import dev.botta.trantor.appServices.useCases.search.SearchQueryBase.SearchResults
import java.time.LocalDateTime

// TODO: agregar filters
// TODO: ver si devolver mas cosas: page, totalPages, maxId, etc
abstract class SearchQueryBase<T, R: SearchResults<T>>(
    val pagination: Pagination? = null,
    val sorting: List<Sorting> = listOf(),
): Query<R> {
    interface SearchResults<T> {
        val items: List<T>
        val page: Int
        val pageSize: Int
        val totalItems: Long?
        val lastCreationDate: LocalDateTime?
    }

    data class Results<T>(
        override val items: List<T>,
        override val page: Int,
        override val pageSize: Int,
        override val totalItems: Long? = null,
        override val lastCreationDate: LocalDateTime? = null
    ): SearchResults<T>
}

typealias SearchQuery<T> = SearchQueryBase<T, SearchResults<T>>
