package dev.botta.trantor.core.lang

fun Class<*>.shortName(): String {
    val parts = this.name.split(".")
    return parts.dropLast(1).joinToString(".") { it.first().toString() } + "." + parts.last()
}
