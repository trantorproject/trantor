rootProject.name = "backend"

include("apps:trantor-app-webapi")
include("libraries:trantor-app-services")
include("libraries:trantor-bom")
include("libraries:trantor-config")
include("libraries:trantor-core")
include("libraries:trantor-data")
include("libraries:trantor-domain")
include("libraries:trantor-event-bus")
include("libraries:trantor-gson")
include("libraries:trantor-service-provider")
include("libraries:trantor-test")
include("libraries:trantor-tx")
include("libraries:trantor-web")

pluginManagement {
    val trantorPluginDir = file("../../trantor-gradle-plugin")
    if (trantorPluginDir.exists()) includeBuild(trantorPluginDir.path)
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
