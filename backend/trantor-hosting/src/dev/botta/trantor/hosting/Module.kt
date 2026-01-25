package dev.botta.trantor.hosting

import dev.botta.trantor.config.*
import dev.botta.trantor.di.*
import kotlin.reflect.KClass
import kotlin.reflect.full.createInstance

interface Module {
    fun compose(services: ServiceRegistry, config: ConfigManager)

    fun initialize(services: ServiceProvider, config: Config)
}

fun ServiceRegistry.addModule(module: Module) {
    addSingleton<Module> { module }
    module.compose(this, this.config)
}

fun <T: Module> ServiceRegistry.addModule(moduleClass: KClass<T>) {
    val module = moduleClass.createInstance()
    addModule(module)
}

inline fun <reified T: Module> ServiceRegistry.addModule() {
    addModule(T::class)
}

fun HostBuilder.addModule(module: Module) {
    services.addModule(module)
}

fun <T: Module> HostBuilder.addModule(moduleClass: KClass<T>) {
    services.addModule(moduleClass)
}

inline fun <reified T: Module> HostBuilder.addModule() {
    addModule(T::class)
}
