package dev.botta.trantor.appServices.useCases.search

import java.lang.Integer.*
import java.util.*

class Pagination(page: Int? = 1, pageSize: Int? = 20, val lastId: UUID? = null) {
    val page = max(1, page ?: 1)
    val pageSize = min(200, pageSize ?: 20)
    val isFirstPage get() = page == 1

    init {
        if (this.page > 1 && lastId == null) {
            throw IllegalArgumentException("lastId must be provided when paginating")
        }
    }
}
