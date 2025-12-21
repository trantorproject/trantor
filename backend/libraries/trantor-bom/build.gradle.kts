plugins {
    `java-platform`
}

javaPlatform {
    allowDependencies()
}

extra.set("POM_NAME", "Trantor BOM (Bill of Materials)")
extra.set("POM_DESCRIPTION", "This Bill of Materials POM can be used to ease dependency management when using Trantor Framework")

dependencies {
    api(platform("org.junit:junit-bom:6.0.1"))
    api(platform("org.assertj:assertj-bom:3.27.6"))
    api(platform("io.ktor:ktor-bom:3.3.2"))

    constraints {
        api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        api("io.mockk:mockk:1.14.6")
        api("io.rest-assured:rest-assured:5.5.6")
        api("dev.botta:cqbus:2.0.0")
        api("dev.botta:kotlin-extensions:1.0.2")
        api("dev.botta:time:1.0.0")
        api("dev.botta:env:2.0.0")
        api("dev.botta:json:1.0.0")
        api("com.google.code.gson:gson:2.13.2")
        api("org.eclipse.jetty:jetty-client:11.0.25")
        api("org.slf4j:slf4j-api:2.0.17")
        api("org.apache.logging.log4j:log4j-core:2.25.2")
        api("org.apache.logging.log4j:log4j-slf4j2-impl:2.25.2")
        api("org.fusesource.jansi:jansi:2.4.2")
        api("com.zaxxer:HikariCP:7.0.2")
        api("org.jooq:jooq:3.20.9")
        api("com.github.f4b6a3:uuid-creator:6.1.1")
    }
}
