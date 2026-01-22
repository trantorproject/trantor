package dev.botta.trantor.app.useCases.search

import dev.botta.trantor.app.useCases.search.SortDirections.Asc

data class Sorting(val property: String, val direction: SortDirections = Asc)
