dependencies {
    implementation(kotlin("reflect"))
    api(project(":libraries:trantor-core"))
    api(project(":libraries:trantor-domain"))
    api("org.junit.jupiter:junit-jupiter")
    api("org.assertj:assertj-core")
    api("io.mockk:mockk")
    api("io.rest-assured:rest-assured")
    runtimeOnly("org.junit.platform:junit-platform-launcher")
}

extra.set("POM_NAME", "Trantor Test")
extra.set("POM_DESCRIPTION", "Utilities to ease testing with Trantor")
