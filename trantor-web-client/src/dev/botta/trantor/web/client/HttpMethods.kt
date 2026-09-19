package dev.botta.trantor.web.client

enum class HttpMethods {
    Get,
    Post,
    Put,
    Patch,
    Delete;

    val value get() = name.uppercase()
}
