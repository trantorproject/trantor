package dev.botta.trantor.test.web

import dev.botta.json.values.JsonValue
import io.restassured.RestAssured
import io.restassured.response.*
import io.restassured.specification.RequestSpecification

class RequestBuilder(private val baseUrl: String = "") {
    private val requestSpecification = RestAssured.given()
    private var whenAction: ((RequestSpecification) -> Response)? = null

    fun asAnonymous() = apply {}

    fun withHeader(name: String, value: String) = apply { requestSpecification.header(name, value) }

    fun body(json: JsonValue) = apply { requestSpecification.jsonBody(json) }

    fun post(endpoint: String, body: JsonValue? = null) = apply {
        whenAction = { it.post(baseUrl + endpoint) }
        if (body != null) body(body)
    }

    fun delete(endpoint: String, body: JsonValue? = null) = apply {
        whenAction = { it.delete(baseUrl + endpoint) }
        if (body != null) body(body)
    }

    fun put(endpoint: String, body: JsonValue? = null) = apply {
        whenAction = { it.put(baseUrl + endpoint) }
        if (body != null) body(body)
    }

    fun patch(endpoint: String, body: JsonValue? = null) = apply {
        whenAction = { it.patch(baseUrl + endpoint) }
        if (body != null) body(body)
    }

    fun get(endpoint: String) = apply {
        whenAction = { it.get(baseUrl + endpoint) }
    }

    fun exec(): ValidatableResponse {
        if (whenAction == null) throw Exception("Must define an action (post, get, put, ...)")
        return whenAction!!(requestSpecification.`when`()).then()
    }
}
