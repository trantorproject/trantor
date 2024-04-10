plugins {
    `java-platform`
}

javaPlatform {
    allowDependencies()
}

dependencies {
    api(platform("org.junit:junit-bom:5.10.2"))
    api(platform("org.assertj:assertj-bom:3.25.3"))

    constraints {
        api("io.mockk:mockk:1.13.10")
    }
}
