package dev.botta.trantor.core.lang

fun String.ifLengthLessThan(size: Int, block: () -> Unit) {
    if (this.length < size) block()
}

fun String.ifLengthNot(size: Int, block: () -> Unit) {
    if (this.length != size) block()
}
