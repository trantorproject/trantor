plugins {
    kotlin("jvm")
}

allprojects {
    if (project.name == "dependencies") return@allprojects

    apply(plugin = "dev.botta.trantor.build.conventions")

    repositories { mavenCentral() }

    group = "dev.botta.trantor"
    version = rootProject.file("VERSION").readText().trim()

    dependencies {
        api(platform(project(":dependencies")))
        implementation(kotlin("stdlib"))
        implementation(kotlin("reflect"))
        testImplementation("org.junit.jupiter:junit-jupiter")
        testImplementation("org.assertj:assertj-core")
        testImplementation("io.mockk:mockk")
    }
}
