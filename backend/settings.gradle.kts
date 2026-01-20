rootProject.name = "backend"

include("libraries:trantor-app-services")
include("libraries:trantor-bom")
include("libraries:trantor-config")
include("libraries:trantor-core")
include("libraries:trantor-data")
include("libraries:trantor-domain")
include("libraries:trantor-events")
include("libraries:trantor-gson")
include("libraries:trantor-hosting")
include("libraries:trantor-service-provider")
include("libraries:trantor-test")
include("libraries:trantor-tx")
include("libraries:trantor-web")
include("libraries:trantor-web-client")

//pluginManagement {
//    val trantorPluginDir = file("../../trantor-gradle-plugin")
//    if (trantorPluginDir.exists()) includeBuild(trantorPluginDir.path)
//}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
