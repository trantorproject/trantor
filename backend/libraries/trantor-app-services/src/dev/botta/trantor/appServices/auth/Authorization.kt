package dev.botta.trantor.appServices.auth

@Target(AnnotationTarget.CLASS)
annotation class Authorization(val roles: Array<String> = [])

fun requiredAuthorizationRoles(clazz: Class<*>): Array<String> {
    val authorization = clazz.annotations.firstOrNull { it is Authorization } as? Authorization
    return authorization?.roles ?: arrayOf()
}
