rootProject.name = "backend"

include("trantor-bom")
include("trantor-core")
include("trantor-test")
include("trantor-web")

pluginManagement {
    val trantorPluginDir = file("../../trantor-gradle-plugin")
    if (trantorPluginDir.exists()) includeBuild(trantorPluginDir.path)
}
