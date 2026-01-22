rootProject.name = "backend"



include("core:trantor-bom")
include("core:trantor-config")
include("core:trantor-core")
include("core:trantor-di")
include("core:trantor-hosting")

include("libraries:trantor-app")
include("libraries:trantor-data")
include("libraries:trantor-domain")
include("libraries:trantor-eventbus")
include("libraries:trantor-gson")
include("libraries:trantor-taskpool")
include("libraries:trantor-test")
include("libraries:trantor-web-client")
include("libraries:trantor-webapp")

//pluginManagement {
//    val trantorPluginDir = file("../../trantor-gradle-plugin")
//    if (trantorPluginDir.exists()) includeBuild(trantorPluginDir.path)
//}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
