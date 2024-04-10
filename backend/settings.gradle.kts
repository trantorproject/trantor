rootProject.name = "backend"

include("trantor-bom")
include("trantor-core")
include("trantor-test")
include("trantor-web")

pluginManagement {
    includeBuild("../../../open-source/kotlin/kotlin-conventions-gradle-plugin")
}
