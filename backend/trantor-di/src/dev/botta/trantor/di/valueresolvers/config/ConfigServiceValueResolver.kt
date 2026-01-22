package dev.botta.trantor.di.valueresolvers.config

import dev.botta.trantor.di.*
import dev.botta.trantor.di.valueresolvers.ServiceValueResolver
import kotlin.reflect.KType
import kotlin.reflect.jvm.jvmErasure

class ConfigServiceValueResolver: ServiceValueResolver {
    override val annotationType = ConfigValue::class

    override fun resolve(annotation: Annotation, paramType: KType, isOptional: Boolean, services: ServiceProvider): ResolvedValue {
        val path = (annotation as ConfigValue).path
        val config = services.config
        val value = if (isOptional) config[path] else config.required(path)
        if (value == null) return ResolvedValue.Skip
        val targetClass = paramType.jvmErasure.java
        return ResolvedValue.Value(convertLiteral(targetClass, value))
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun convertLiteral(targetType: Class<*>, value: String): Any = when (targetType) {
        String::class.java -> value
        Int::class.java, Integer::class.java -> value.toInt()
        Long::class.java, java.lang.Long::class.java -> value.toLong()
        Double::class.java, java.lang.Double::class.java -> value.toDouble()
        Float::class.java, java.lang.Float::class.java -> value.toFloat()
        Boolean::class.java, java.lang.Boolean::class.java -> value.equals("true", ignoreCase = true) || value == "1"
        else -> throw IllegalArgumentException("Cannot convert config value '$value' to ${targetType.name}")
    }
}
