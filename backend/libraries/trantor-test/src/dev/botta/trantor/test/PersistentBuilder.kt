package dev.botta.trantor.test

abstract class PersistentBuilder<T> {
    private val pendingSaves = mutableListOf<() -> Unit>()
    private var save = true

    protected fun <C> child(builder: PersistentBuilder<C>): C {
        val childObj = builder.doBuild()
        pendingSaves.add { builder.save(childObj) }
        return childObj
    }

    fun noSave() = apply { save = false }

    protected abstract fun doBuild(): T

    protected abstract fun doSave(obj: T)

    private fun save(obj: T) {
        if (!save) return
        doSave(obj)
        pendingSaves.forEach { it() }
    }

    fun build(): T {
        return doBuild().also { save(it) }
    }
}
