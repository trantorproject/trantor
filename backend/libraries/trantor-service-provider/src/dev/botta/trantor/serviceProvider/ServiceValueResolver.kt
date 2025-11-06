package dev.botta.trantor.serviceProvider

import kotlin.reflect.KClass

interface ServiceValueResolver {
    val annotationType: KClass<*>
    fun resolve(annotation: Annotation, paramType: Class<*>, services: ServiceProvider): Any
}
