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
        api("dev.botta:kotlin-extensions:1.0.2")
        api("dev.botta:time:1.0.0")
        api("dev.botta:env:2.0.0")
        api("dev.botta:json:1.0.0")
        api("com.google.code.gson:gson:2.10.1")
    }
}
