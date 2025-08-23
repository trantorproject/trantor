package dev.botta.trantor.appServices.useCases.search

import java.lang.Integer.max
import java.lang.Integer.min
import java.time.LocalDateTime

class Pagination(page: Int? = 1, pageSize: Int? = 20, val lastCreationDate: LocalDateTime? = null) {
    val page = max(1, page ?: 1)
    val pageSize = min(200, pageSize ?: 20)
    val isFirstPage get() = page == 1

    init {
        if (this.page > 1 && lastCreationDate == null) {
            throw IllegalArgumentException("lastCreationDate must be provided when paginating")
        }
    }
}
