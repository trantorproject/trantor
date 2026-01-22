package dev.botta.trantor.web.appModule.transformers

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.app.useCases.search.*
import dev.botta.trantor.web.appModule.RequestToJsonTransformer
import io.javalin.http.Context
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf

class SearchQueryRequestToJsonTransformer: RequestToJsonTransformer {
    override fun transform(context: Context, json: JsonObject?, type: KClass<*>) {
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
