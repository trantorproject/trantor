plugins {
    kotlin("jvm")
    id("dev.botta.trantor.build.conventions")
}

group = "dev.botta.trantor"
version = rootProject.file("VERSION").readText().trim()

allprojects {
    if (project.name.startsWith("framework-")) return@allprojects

    apply(plugin = "dev.botta.trantor.build.conventions")

    repositories { mavenCentral() }

    group = "dev.botta.trantor"
    version = rootProject.file("VERSION").readText().trim()

    dependencies {
        api(platform(project(":framework-platform")))
        implementation(kotlin("stdlib"))
        implementation(kotlin("reflect"))
        testImplementation("org.junit.jupiter:junit-jupiter")
        testImplementation("org.assertj:assertj-core")
        testImplementation("io.mockk:mockk")
    }
}

tasks.named<Copy>("processResources") {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}
