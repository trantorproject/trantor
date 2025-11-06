package dev.botta.trantor.config.serviceProvider

import dev.botta.trantor.config.Config
import dev.botta.trantor.serviceProvider.*

class ConfigServiceValueResolver: ServiceValueResolver {
    override val annotationType = ConfigValue::class

    override fun resolve(annotation: Annotation, paramType: Class<*>, services: ServiceProvider): Any {
        val path = (annotation as ConfigValue).path
        val config = services.get<Config>()
        val value = config.required(path)
        return convertLiteral(paramType, value)
    }

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
