package dev.botta.trantor.webApi.appModule.transformers

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.appServices.useCases.search.*
import dev.botta.trantor.webApi.appModule.RequestToJsonTransformer
import io.ktor.server.routing.*
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf

class SearchQueryRequestToJsonTransformer: RequestToJsonTransformer {
    override fun transform(context: RoutingContext, json: JsonObject?, type: KClass<*>) {
        if (!type.isSubclassOf(SearchQueryBase::class)) return
        json?.set("pagination", Json.obj(
            "page" to json["page"],
            "pageSize" to json["pageSize"],
            "lastCreationDate" to json["lastCreationDate"],
        ))
        if (json?.get("sortBy") != null) {
            val sortByList = json["sortBy"]?.asString()?.split(",")?.map { it.trim() } ?: listOf()
            val sortDirectionList = json["sortDirection"]?.asString()?.split(",")?.map { it.trim() } ?: listOf()
            json["sorting"] = Json.array(
                sortByList.mapIndexed { index, sortBy -> Json.obj(
                    "property" to sortBy,
                    "direction" to if (index < sortDirectionList.size) sortDirectionList[index] else SortDirections.Asc,
                ) }
            )
        }
    }
}
