package dev.botta.trantor.serialization.gson

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.json.values.JsonValue
import dev.botta.trantor.domain.Id
import dev.botta.trantor.primitives.lang.Maybe
import dev.botta.trantor.primitives.serialization.Description
import dev.botta.trantor.primitives.serialization.JsonSchemaError
import dev.botta.trantor.primitives.validation.NullOrNotBlank
import dev.botta.trantor.serialization.gson.adapters.HierarchyTypeAdapterFactory
import dev.botta.trantor.serialization.gson.adapters.kotlinReflective.KotlinReflectiveTypeAdapterFactory
import dev.botta.trantor.serialization.gson.adapters.kotlinReflective.getSerializedNames
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Negative
import jakarta.validation.constraints.NegativeOrZero
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.math.BigInteger
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import kotlin.reflect.KTypeProjection
import kotlin.reflect.full.createType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.withNullability

/**
 * The JSON Schema of what [GsonSerializer] reads, following the rules it reads by (see docs/trantor-gson.md): a Kotlin
 * class by the parameters of its primary constructor, the types of the domain as the strings they are read from, and
 * what the application registered with the schema it gave.
 *
 * The shape is the one kotlinx-schema gives, which the providers of trantor-ai already take: the root inline, and the
 * classes, enums and hierarchies it uses in `$defs`. Hierarchies and nullable objects go as `anyOf`, since neither
 * OpenAI nor Anthropic take `oneOf`.
 */
internal class GsonJsonSchemas(
    private val gson: Gson,
    /** A type registered with an adapter and the schema it gave, or null when it gave none. */
    private val registered: Map<Class<*>, JsonObject?>,
    private val hierarchies: List<HierarchyTypeAdapterFactory<*>>,
) {
    fun of(type: KType): JsonObject = Walk(type).run()

    private inner class Walk(private val root: KType) {
        private val definitions = linkedMapOf<String, JsonObject>()
        private val names = mutableMapOf<String, String>()
        private val rootKey = keyOf(root.withNullability(false))
        private var rootDescribed = false

        fun run(): JsonObject {
            val schema = describe(root, (root.classifier as? KClass<*>)?.simpleName ?: "the type")
            if (definitions.isNotEmpty()) schema[DEFS] = JsonObject(definitions.toList())

            return schema
        }

        private fun describe(type: KType, path: String): JsonObject {
            val schema = describeNotNull(type.withNullability(false), path)

            return if (type.isMarkedNullable) orNull(schema) else schema
        }

        private fun describeNotNull(type: KType, path: String): JsonObject {
            val klass = type.classifier as? KClass<*>
                ?: throw JsonSchemaError("$path is of ${type}, which a schema cannot tell without its type")

            if (registered.containsKey(klass.java)) {
                val schema = registered[klass.java] ?: throw noSchema(path, klass)
                return copy(schema)
            }

            hierarchies.firstOrNull { it.baseType == klass.java }?.let { return hierarchy(type, it, path) }

            return when {
                klass == String::class -> Json.obj("type" to "string")
                klass == Char::class -> Json.obj("type" to "string", "minLength" to 1, "maxLength" to 1)
                klass in INTEGERS -> Json.obj("type" to "integer")
                klass in DECIMALS -> Json.obj("type" to "number")
                klass == Boolean::class -> Json.obj("type" to "boolean")
                klass == UUID::class -> Json.obj("type" to "string", "format" to "uuid")
                // Every Id is read by IdTypeAdapterFactory, from its uuid
                klass.isSubclassOf(Id::class) -> Json.obj("type" to "string", "format" to "uuid")
                klass == Any::class -> Json.obj()
                klass.java.isEnum -> enum(type, klass)
                // null is always something it takes: of what can be null, a change to null; of what cannot, no change
                klass == Maybe::class -> describe(argument(type, 0, path).withNullability(true), path)
                klass.isValue -> describe(valueOf(type, klass), path)
                klass.isSubclassOf(Map::class) ->
                    Json.obj("type" to "object", "additionalProperties" to describe(argument(type, 1, path), "$path[]"))
                klass.isSubclassOf(Iterable::class) ->
                    Json.obj("type" to "array", "items" to describe(argument(type, 0, path), "$path[]"))
                klass.java.isArray -> {
                    // An IntArray and the like have no type argument: what they hold is in their Java class
                    val item = type.arguments.firstOrNull()?.type ?: klass.java.componentType.kotlin.createType()
                    Json.obj("type" to "array", "items" to describe(item, "$path[]"))
                }
                else -> objectOf(type, klass, path)
            }
        }

        private fun objectOf(type: KType, klass: KClass<*>, path: String): JsonObject {
            if (gson.getAdapter(klass.java) !is KotlinReflectiveTypeAdapterFactory.Adapter<*>) {
                throw noSchema(path, klass)
            }

            // The root is written inline, and a class that contains it refers to it by the root of the document
            if (keyOf(type) == rootKey) {
                if (rootDescribed) return Json.obj(REF to "#")
                rootDescribed = true
                return describeObject(type, klass, path)
            }

            return definition(type) { describeObject(type, klass, path) }
        }

        private fun describeObject(
            type: KType,
            klass: KClass<*>,
            path: String,
            label: Pair<String, String>? = null,
        ): JsonObject {
            val constructor = klass.primaryConstructor ?: throw noSchema(path, klass)
            val bindings = klass.typeParameters.zip(type.arguments.map { it.type }).toMap()
            val properties = Json.obj()
            val required = mutableListOf<String>()

            label?.let { (field, value) ->
                properties[field] = Json.obj("type" to "string", "const" to value)
                required += field
            }

            for (parameter in constructor.parameters) {
                @Suppress("UNCHECKED_CAST")
                val name = parameter.getSerializedNames(klass.java as Class<Any>).firstOrNull() ?: continue
                if (name == label?.first) continue

                val parameterType = bound(parameter.type, bindings)
                val annotations = annotationsOf(parameter, klass)
                val schema = describe(parameterType, "$path.$name")
                constrain(schema, annotations)
                annotations.filterIsInstance<Description>().firstOrNull()?.let { schema["description"] = it.value }

                properties[name] = schema
                if (!parameter.isOptional && !parameter.type.isMarkedNullable) required += name
            }

            val schema = Json.obj("type" to "object", "properties" to properties)
            if (required.isNotEmpty()) schema["required"] = Json.array(required)
            schema["additionalProperties"] = false
            klass.findAnnotation<Description>()?.let { schema["description"] = it.value }

            return schema
        }

        private fun hierarchy(type: KType, factory: HierarchyTypeAdapterFactory<*>, path: String) =
            definition(type) {
                val subtypes = factory.subtypes.map { (label, subtype) ->
                    val subtypeType = subtype.kotlin.createType()
                    definition(subtypeType) {
                        describeObject(subtypeType, subtype.kotlin, path, factory.typeFieldName to label)
                    }
                }

                Json.obj("anyOf" to Json.array(subtypes))
            }

        private fun enum(type: KType, klass: KClass<*>) = definition(type) {
            val names = klass.java.enumConstants.map {
                val constant = it as Enum<*>
                klass.java.getField(constant.name).getAnnotation(SerializedName::class.java)?.value ?: constant.name
            }

            Json.obj("type" to "string", "enum" to Json.array(names))
        }

        /**
         * A reference to the definition of [type], which [describe] writes the first time. The name is taken before
         * it is written, so that a type that contains itself refers to it instead of going around forever.
         */
        private fun definition(type: KType, describe: () -> JsonObject): JsonObject {
            val key = keyOf(type)
            val existing = names[key]
            if (existing != null) return ref(existing)

            val name = nameOf(type)
            names[key] = name
            definitions[name] = Json.obj()
            definitions[name] = describe()

            return ref(name)
        }

        private fun nameOf(type: KType): String {
            val simple = simpleNameOf(type)
            if (!definitions.containsKey(simple)) return simple

            // Another class has the same simple name, so this one keeps its package
            return keyOf(type)
        }
    }

    private fun simpleNameOf(type: KType): String {
        val klass = type.classifier as KClass<*>
        val arguments = type.arguments.mapNotNull { it.type }.joinToString("") { "Of" + simpleNameOf(it) }

        return klass.simpleName + arguments
    }

    private fun keyOf(type: KType): String {
        val klass = type.classifier as? KClass<*> ?: return type.toString()
        val arguments = type.arguments.mapNotNull { it.type }

        if (arguments.isEmpty()) return klass.qualifiedName.orEmpty()

        return klass.qualifiedName + arguments.joinToString(",", "<", ">") { keyOf(it) }
    }

    private fun argument(type: KType, index: Int, path: String) = type.arguments.getOrNull(index)?.type
        ?: throw JsonSchemaError("$path is of $type, which a schema cannot tell without the types it holds")

    private fun valueOf(type: KType, klass: KClass<*>): KType {
        val parameter = klass.primaryConstructor!!.parameters.single()
        return bound(parameter.type, klass.typeParameters.zip(type.arguments.map { it.type }).toMap())
    }

    /** [type] with the type parameters of the class it is in replaced by the types the class was declared with. */
    private fun bound(type: KType, bindings: Map<KTypeParameter, KType?>): KType {
        val classifier = type.classifier
        if (classifier is KTypeParameter) {
            val bound = bindings[classifier] ?: Any::class.createType(nullable = true)
            return if (type.isMarkedNullable) bound.withNullability(true) else bound
        }
        if (type.arguments.isEmpty()) return type

        val arguments = type.arguments.map { projection ->
            projection.type?.let { KTypeProjection(projection.variance, bound(it, bindings)) } ?: projection
        }

        return (classifier as KClass<*>).createType(arguments, type.isMarkedNullable, type.annotations)
    }

    /**
     * The annotations of a parameter and of the field behind it: Kotlin puts an annotation of Java on both, or only
     * on the field when it is written `@field:NotBlank`.
     */
    private fun annotationsOf(parameter: KParameter, klass: KClass<*>): List<Annotation> {
        val field = runCatching { klass.java.getDeclaredField(parameter.name!!) }.getOrNull()
        return parameter.annotations + field?.annotations.orEmpty()
    }

    /** What the validations of Jakarta will ask for, said in the schema so that it is asked for up front. */
    private fun constrain(schema: JsonObject, annotations: List<Annotation>) {
        val kind = kindOf(schema) ?: return
        val text = kind == "string"
        val list = kind == "array"

        for (annotation in annotations) {
            when (annotation) {
                is NotBlank, is NullOrNotBlank -> if (text) schema["minLength"] = 1
                is NotEmpty -> when {
                    text -> schema["minLength"] = 1
                    list -> schema["minItems"] = 1
                    kind == "object" -> schema["minProperties"] = 1
                }
                is Size -> {
                    val (min, max) = if (text) "minLength" to "maxLength" else "minItems" to "maxItems"
                    if (text || list) {
                        if (annotation.min > 0) schema[min] = annotation.min
                        if (annotation.max < Int.MAX_VALUE) schema[max] = annotation.max
                    }
                }
                is Min -> schema["minimum"] = annotation.value
                is Max -> schema["maximum"] = annotation.value
                is DecimalMin -> {
                    val keyword = if (annotation.inclusive) "minimum" else "exclusiveMinimum"
                    schema[keyword] = number(annotation.value)
                }
                is DecimalMax -> {
                    val keyword = if (annotation.inclusive) "maximum" else "exclusiveMaximum"
                    schema[keyword] = number(annotation.value)
                }
                is Positive -> schema["exclusiveMinimum"] = 0
                is PositiveOrZero -> schema["minimum"] = 0
                is Negative -> schema["exclusiveMaximum"] = 0
                is NegativeOrZero -> schema["maximum"] = 0
                is Pattern -> schema["pattern"] = annotation.regexp
                is Email -> schema["format"] = "email"
            }
        }
    }

    /** The type a schema is for, leaving out the null a nullable one also takes. */
    private fun kindOf(schema: JsonObject): String? {
        val type = schema["type"] ?: return null
        return type.asString() ?: type.asArray()?.mapNotNull { it.asString() }?.firstOrNull { it != "null" }
    }

    private fun number(value: String): JsonValue {
        val decimal = BigDecimal(value)
        val whole = decimal.stripTrailingZeros().scale() <= 0

        return if (whole) Json.value(decimal.toLong()) else Json.value(decimal.toDouble())
    }

    /** [schema], also taking null: in its `type` when it has a single one, and as another option when not. */
    private fun orNull(schema: JsonObject): JsonObject {
        if (schema.isEmpty()) return schema

        val type = schema["type"]?.asString()
        if (type != null && !schema.containsKey("enum") && !schema.containsKey("const")) {
            schema["type"] = Json.array(type, "null")
            return schema
        }

        return Json.obj("anyOf" to Json.array(schema, Json.obj("type" to "null")))
    }

    private fun ref(name: String) = Json.obj(REF to "#/$DEFS/$name")

    private fun copy(schema: JsonObject) = Json.parse(schema.toString()).asObject()!!

    private fun noSchema(path: String, klass: KClass<*>) = JsonSchemaError(
        "$path is read by an adapter of ${klass.qualifiedName} that gave no schema, so what it reads cannot be told. " +
            "Give it with registerTypeAdapter(type, adapter, schema), or with registerSchema(type, schema)",
    )

    private companion object {
        const val DEFS = $$"$defs"
        const val REF = $$"$ref"

        val INTEGERS = setOf(Int::class, Long::class, Short::class, Byte::class, BigInteger::class)
        val DECIMALS = setOf(Double::class, Float::class, BigDecimal::class)
    }
}
