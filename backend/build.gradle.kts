plugins {
    kotlin("jvm") version "1.9.23"
    id("dev.botta.kotlin-conventions") version "1.0.0"
}

allprojects {
    if (project.name == "trantor-bom") return@allprojects

    apply(plugin = "dev.botta.kotlin-conventions")

    repositories { mavenCentral() }

    group = "dev.botta.trantor"
    version = rootProject.file("VERSION").readText().trim()

    dependencies {
        api(platform(project(":trantor-bom")))
        implementation(kotlin("stdlib"))
        implementation(kotlin("reflect"))
        testImplementation("org.junit.jupiter:junit-jupiter")
        testImplementation("org.assertj:assertj-core")
        testImplementation("io.mockk:mockk")
    }
}
