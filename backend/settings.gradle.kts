rootProject.name = "backend"

include("apps:trantor-app-rest-api")
include("libraries:trantor-app-services")
include("libraries:trantor-bom")
include("libraries:trantor-core")
include("libraries:trantor-data")
include("libraries:trantor-domain")
include("libraries:trantor-serialization")
include("libraries:trantor-rest-api")
include("libraries:trantor-test")
include("libraries:trantor-tx")
include("libraries:trantor-web")

pluginManagement {
    val trantorPluginDir = file("../../trantor-gradle-plugin")
    if (trantorPluginDir.exists()) includeBuild(trantorPluginDir.path)
}
