package dev.botta.trantor.serviceProvider.valueresolvers

import dev.botta.trantor.serviceProvider.ResolvedValue
import dev.botta.trantor.serviceProvider.ServiceProvider
import kotlin.reflect.KClass
import kotlin.reflect.KType

interface ServiceValueResolver {
    val annotationType: KClass<*>
    fun resolve(annotation: Annotation, paramType: KType, isOptional: Boolean, services: ServiceProvider): ResolvedValue
}
