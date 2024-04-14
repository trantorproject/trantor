package dev.botta.trantor.appServices.search

import java.lang.Integer.max
import java.time.LocalDateTime

class Pagination(page: Int = 1, val lastCreationDate: LocalDateTime? = null) {
    val page = max(1, page)

    init {
        if (page > 1 && lastCreationDate == null) {
            throw IllegalArgumentException("lastCreationDate must be provided when paginating")
        }
    }
}
