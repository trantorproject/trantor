package dev.botta.trantor.appServices.useCases.search

import dev.botta.trantor.appServices.useCases.search.SortDirections.Asc

data class Sorting(val property: String, val direction: SortDirections = Asc)
