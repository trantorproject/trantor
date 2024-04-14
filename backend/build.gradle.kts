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
        api("dev.botta:kotlin-extensions")
        implementation("dev.botta:time")
        implementation("dev.botta:env")
        implementation(kotlin("stdlib"))
        implementation(kotlin("reflect"))
        if (project.name != "trantor-test") testImplementation(project(":libraries:trantor-test"))
    }
}
