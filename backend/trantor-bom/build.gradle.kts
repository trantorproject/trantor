plugins {
    `java-platform`
}

javaPlatform {
    allowDependencies()
}

extra.set("POM_NAME", "Trantor BOM (Bill of Materials)")
extra.set("POM_DESCRIPTION", "This Bill of Materials POM can be used to ease dependency management when using Trantor Framework")

dependencies {
    api(platform("org.junit:junit-bom:6.0.2"))
    api(platform("org.assertj:assertj-bom:3.27.6"))
    api(platform("software.amazon.awssdk:bom:2.41.4"))

    constraints {
        api("io.mockk:mockk:1.14.7")
        api("io.rest-assured:rest-assured:6.0.0")
        api("dev.botta:cqbus:2.0.1")
        api("dev.botta:kotlin-extensions:1.0.2")
        api("dev.botta:time:1.0.0")
        api("dev.botta:env:2.0.0")
        api("dev.botta:json:1.0.0")
        api("com.google.code.gson:gson:2.13.2")
        api("io.javalin:javalin:6.7.0")
        api("org.eclipse.jetty:jetty-client:11.0.26")
        api("org.slf4j:slf4j-api:2.0.17")
        api("org.apache.logging.log4j:log4j-core:2.25.3")
        api("org.apache.logging.log4j:log4j-slf4j2-impl:2.25.3")
        api("org.fusesource.jansi:jansi:2.4.2")
        api("com.zaxxer:HikariCP:7.0.2")
        api("org.jooq:jooq:3.20.10")
        api("com.github.f4b6a3:uuid-creator:6.1.1")
    }
}
