package dev.botta.trantor.serialization.gson.adapters

import com.google.gson.ExclusionStrategy
import com.google.gson.FieldAttributes

/**
 * Leaves out the fields Kotlin makes for delegated properties, like `upper$delegate` for `val upper by lazy { }`. What
 * they hold is the delegate, a `Lazy` or whatever the property delegates to, never the value of the property.
 */
class DelegatedPropertiesExclusion: ExclusionStrategy {
    override fun shouldSkipField(field: FieldAttributes) = field.name.endsWith(DELEGATE_SUFFIX)

    override fun shouldSkipClass(clazz: Class<*>) = false

    private companion object {
        const val DELEGATE_SUFFIX = "\$delegate"
    }
}
