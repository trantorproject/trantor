dependencies {
    api(project(":libraries:trantor-core"))
    api(project(":libraries:trantor-domain"))
    api("org.junit.jupiter:junit-jupiter")
    api("org.assertj:assertj-core")
    api("io.mockk:mockk")
    api("io.rest-assured:rest-assured")
    runtimeOnly("org.junit.platform:junit-platform-launcher")
}
