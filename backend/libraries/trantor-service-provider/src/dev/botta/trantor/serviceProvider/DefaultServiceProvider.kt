package dev.botta.trantor.serviceProvider

import dev.botta.trantor.serviceProvider.ServiceLifetimes.*
import dev.botta.trantor.serviceProvider.config.ConfigServiceValueResolver
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.jvmErasure

class DefaultServiceProvider(registry: ServiceRegistry): ServiceProvider(registry) {
    private val singletonCache: MutableMap<String, Any> = mutableMapOf()
    private var inScope: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
    private val scopeCache: ThreadLocal<MutableMap<String, Any>> = ThreadLocal.withInitial { mutableMapOf() }
    private val valueResolvers by lazy {
        listOf(
            ConfigServiceValueResolver(),
            *getAll<ServiceValueResolver>().toTypedArray(),
        ).associateBy { it.annotationType }
    }

    override fun <T: Any> getOrDefault(type: Class<T>, key: String?, default: () -> T) = tryGet(type, key) ?: default()

    override fun <T: Any> getOrDefault(type: Class<T>, default: () -> T) = tryGet(type) ?: default()

    override fun <T: Any> get(type: Class<T>, key: String?) =
        tryGet(type, key) ?: throw ServiceNotRegisteredError(type, key)

    @Synchronized
    override fun <T: Any> has(type: Class<T>, key: String?) = registry.any { it.serviceType == type && it.key == key }

    @Synchronized
    override fun <T: Any> tryGet(type: Class<T>, key: String?): T? {
        val descriptor = registry.lastOrNull { it.serviceType == type && it.key == key } ?: return null
        return getInstanceFor(descriptor)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T: Any> getInstanceFor(descriptor: ServiceDescriptor<*>): T? {
        return when (descriptor.lifetime) {
            Transient -> descriptor.createInstance()
            Singleton -> singletonCache.getOrPut(descriptor.serviceId) { descriptor.createInstance() }
            Scoped -> {
                if (inScope.get() == false) return null
                scopeCache.get().getOrPut(descriptor.serviceId) { descriptor.createInstance() }
            }
        } as T
    }

    @Synchronized
    override fun <T: Any> getAll(type: Class<T>, key: String?): List<T> {
        val descriptors = registry.filter { it.serviceType == type && it.key == key }
        return descriptors.mapNotNull { getInstanceFor(it) }
    }

    private fun <T: Any> ServiceDescriptor<T>.createInstance(): T {
        val instance = implementationFactory(this@DefaultServiceProvider)
        registry.getConfigurations(serviceType, key).forEach { it(instance) }
        return instance
    }

    override fun enterScope() {
        scopeCache.get().clear()
        inScope.set(true)
    }

    override fun leaveScope() {
        scopeCache.get().clear()
        inScope.set(false)
    }

    override fun <T: Any> create(type: KClass<T>): T {
        val ctor = type.primaryConstructor
            ?: throw IllegalArgumentException("Class ${type.simpleName} must have a primary constructor")

        val args = mutableMapOf<kotlin.reflect.KParameter, Any?>()

        for (param in ctor.parameters) {
            val kType = param.type
            val paramType = kType.jvmErasure.java

            val annotation = param.annotations.firstOrNull { valueResolvers.containsKey(it.annotationClass) }
            // Try using registered value resolver
            if (annotation != null) {
                val resolver = valueResolvers[annotation.annotationClass]!!
                val resolvedValue = resolver.resolve(annotation, kType, param.isOptional, this)
                if (resolvedValue is ResolvedValue.Value) {
                    args[param] = resolvedValue.value
                }
                continue
            }

            val dependency = tryGet(paramType)
            when {
                dependency != null -> args[param] = dependency
                param.isOptional -> Unit // use default value
                else -> throw ServiceNotRegisteredError(
                    "Missing dependency for parameter '${param.name}' of type ${paramType.name} in ${type.simpleName}"
                )
            }
        }

        return ctor.callBy(args)
    }
}
