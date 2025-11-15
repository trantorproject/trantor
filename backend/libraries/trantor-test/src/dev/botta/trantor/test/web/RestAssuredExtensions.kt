package dev.botta.trantor.test.web

import dev.botta.json.Json
import dev.botta.json.values.JsonValue
import dev.botta.trantor.domain.Id
import io.restassured.response.ValidatableResponse
import io.restassured.specification.RequestSpecification
import org.hamcrest.CoreMatchers
import org.hamcrest.CoreMatchers.`is`

fun RequestSpecification.jsonBody(json: JsonValue): RequestSpecification {
    return this.body(json.toString())
}

fun RequestSpecification.jsonBody(vararg pairs: Pair<String, Any?>): RequestSpecification {
    return jsonBody(Json.obj(pairs.toList()))
}

fun ValidatableResponse.succeeds(statusCode: Int = 200): ValidatableResponse = this.statusCode(statusCode)

fun ValidatableResponse.isArrayLengthOf(length: Int): ValidatableResponse = this.body("size()", `is`(length))

fun ValidatableResponse.failsWith(statusCode: Int): ValidatableResponse = this.statusCode(statusCode)

fun ValidatableResponse.failsWithForbidden(): ValidatableResponse = this.statusCode(403)

fun ValidatableResponse.failsWithNotFound(): ValidatableResponse = this.statusCode(404)

fun <T> ValidatableResponse.returningId(): Id {
    val id = this.body("id", CoreMatchers.notNullValue()).extract().path<String>("id")
    return Id(id)
}

fun ValidatableResponse.returningSessionToken(): String {
    return body("sessionToken", CoreMatchers.notNullValue()).extract().path("sessionToken")
}
