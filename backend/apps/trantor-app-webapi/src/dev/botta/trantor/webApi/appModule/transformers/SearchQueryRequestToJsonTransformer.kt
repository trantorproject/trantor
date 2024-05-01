package dev.botta.trantor.webApi.appModule.transformers

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.appServices.useCases.search.SearchQuery
import dev.botta.trantor.webApi.appModule.RequestToJsonTransformer
import io.javalin.http.Context
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf

class SearchQueryRequestToJsonTransformer: RequestToJsonTransformer {
    override fun transform(context: Context, json: JsonObject?, type: KClass<*>) {
        if (!type.isSubclassOf(SearchQuery::class)) return
        json?.set("pagination", Json.obj(
            "page" to json["page"],
            "lastCreationDate" to json["lastCreationDate"],
        ))
        if (json?.get("sortBy") != null) {
            json["sorting"] = Json.obj(
                "property" to json["sortBy"],
                "direction" to json["sortDirection"],
            )
        }
    }
}
