plugins {
    kotlin("jvm") version "2.1.20"
    id("dev.botta.kotlin-conventions") version "0.4.2"
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
        if (project.name != "trantor-test") testImplementation(project(":libraries:trantor-test"))
    }

    kotlin {
        jvmToolchain(23)
    }
}
