package dev.botta.trantor.core.auth

@Target(AnnotationTarget.CLASS)
annotation class RolesAuthorization(val roles: Array<String> = [])

fun requiredAuthorizationRoles(clazz: Class<*>): Array<String> {
    val authorization = clazz.annotations.firstOrNull { it is RolesAuthorization } as? RolesAuthorization
    return authorization?.roles ?: arrayOf()
}
