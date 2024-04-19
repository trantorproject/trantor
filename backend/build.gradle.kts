plugins {
    kotlin("jvm") version "1.9.23"
    id("dev.botta.kotlin-conventions") version "0.1.0"
}

allprojects {
    if (project.name == "trantor-bom") return@allprojects

    apply(plugin = "dev.botta.kotlin-conventions")

    repositories { mavenCentral() }

    group = "dev.botta.trantor"
    version = rootProject.file("VERSION").readText().trim()

    dependencies {
        api(platform(project(":libraries:trantor-bom")))
        api(kotlin("stdlib"))
        api(kotlin("reflect"))
        implementation("org.slf4j:slf4j-simple")
        if (project.name != "trantor-test") testImplementation(project(":libraries:trantor-test"))
    }
}
