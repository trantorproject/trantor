package dev.botta.trantor.appServices.useCases.search

import java.lang.Integer.max
import java.time.LocalDateTime

// TODO: lastCreationDate y maxId opcionales. Podes usar cualquiera depende el caso. Quizas es lastUpdatedAt
class Pagination(page: Int = 1, val lastCreationDate: LocalDateTime? = null) {
    val page = max(1, page)

    init {
        if (page > 1 && lastCreationDate == null) {
            throw IllegalArgumentException("lastCreationDate must be provided when paginating")
        }
    }
}
