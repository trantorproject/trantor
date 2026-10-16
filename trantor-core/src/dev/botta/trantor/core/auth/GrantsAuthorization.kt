package dev.botta.trantor.core.auth

@Target(AnnotationTarget.CLASS)
annotation class GrantsAuthorization(val grants: Array<String> = [])

fun requiredAuthorizationGrants(clazz: Class<*>): Array<String> {
    val authorization = clazz.annotations.firstOrNull { it is GrantsAuthorization } as? GrantsAuthorization
    return authorization?.grants ?: arrayOf()
}
