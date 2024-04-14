package dev.botta.trantor.test

class TestExamples<T>(examples: List<T>) {
    private var examples = ThreadLocal.withInitial { examples }
    private var current = -1

    constructor(vararg examples: T): this(examples.toList())

    fun one(): T {
        current++
        return examples.get()[current % examples.get().size]
    }
}
