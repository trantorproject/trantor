package dev.botta.trantor.primitives.lang

fun Class<*>.shortName(): String {
    val parts = this.name.split(".")
    return parts.dropLast(1).joinToString(".") { it.first().toString() } + "." + parts.last()
}
