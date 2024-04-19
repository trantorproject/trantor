package dev.botta.trantor.appServices.useCases.search

import dev.botta.cqbus.requests.Query
import dev.botta.trantor.appServices.useCases.search.SearchQuery.Results
import java.time.LocalDateTime

abstract class SearchQuery<T>(
    val pagination: Pagination? = null,
    val sorting: Sorting? = null,
): Query<Results<T>> {
    data class Results<T>(
        val items: List<T>,
        val totalItems: Long? = null,
        val lastCreationDate: LocalDateTime? = null
    )
}
