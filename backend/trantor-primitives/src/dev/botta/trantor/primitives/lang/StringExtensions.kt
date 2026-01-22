package dev.botta.trantor.primitives.lang

fun String.ifLengthLessThan(size: Int, block: () -> Unit) {
    if (this.length < size) block()
}

fun String.ifLengthNot(size: Int, block: () -> Unit) {
    if (this.length != size) block()
}
