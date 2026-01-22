rootProject.name = "backend"

include("trantor-bom")
include("trantor-core")
include("trantor-config")
include("trantor-primitives")
include("trantor-di")
include("trantor-hosting")
include("trantor-app")
include("trantor-data")
include("trantor-domain")
include("trantor-gson")
include("trantor-taskpool")
include("trantor-test")
include("trantor-web-client")
include("trantor-webapp")

//pluginManagement {
//    val trantorPluginDir = file("../../trantor-gradle-plugin")
//    if (trantorPluginDir.exists()) includeBuild(trantorPluginDir.path)
//}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
