package dev.botta.trantor.primitives.lang

fun Any.describe(vararg params: Pair<String, Any?>): String =
    "${this::class.simpleName}(${params.joinToString(", ") { (k, v) -> "$k=$v" }})"

fun Any.describe(vararg parts: String): String = "${this::class.simpleName}(${parts.joinToString(", ")})"

fun Any.describe(value: Any?): String = "${this::class.simpleName}($value)"

fun Any.describe(): String = "${this::class.simpleName}()"
