dependencies {
    api(kotlin("stdlib"))
    api("dev.botta:kotlin-extensions")
    api("dev.botta:time")
    api("dev.botta:env")
    api("dev.botta:json")
    api("io.javalin:javalin")
    api("org.eclipse.jetty:jetty-client")
    implementation(project(":libraries:trantor-core"))
    implementation("org.slf4j:slf4j-simple")
}
