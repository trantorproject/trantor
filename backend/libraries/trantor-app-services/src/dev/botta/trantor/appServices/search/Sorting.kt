package dev.botta.trantor.appServices.search

import dev.botta.trantor.appServices.search.SortDirections.Asc

data class Sorting(val property: String, val direction: SortDirections = Asc)
