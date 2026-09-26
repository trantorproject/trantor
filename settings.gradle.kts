rootProject.name = "trantor"

include("trantor-ai")
include("trantor-aws")
include("trantor-bom")
include("trantor-core")
include("trantor-config")
include("trantor-data")
include("trantor-di")
include("trantor-domain")
include("trantor-gson")
include("trantor-hosting")
include("trantor-opentelemetry")
include("trantor-primitives")
include("trantor-queues-sqs")
include("trantor-taskpool")
include("trantor-test")
include("trantor-web")
include("trantor-web-client")

//pluginManagement {
//    val trantorPluginDir = file("../../trantor-gradle-plugin")
//    if (trantorPluginDir.exists()) includeBuild(trantorPluginDir.path)
//}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
