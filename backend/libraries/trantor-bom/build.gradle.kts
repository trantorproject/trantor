plugins {
    `java-platform`
}

javaPlatform {
    allowDependencies()
}

dependencies {
    api(platform("org.junit:junit-bom:5.12.2"))
    api(platform("org.assertj:assertj-bom:3.27.3"))

    constraints {
        api("io.mockk:mockk:1.14.2")
        api("io.rest-assured:rest-assured:5.5.5")
        api("dev.botta:cqbus:1.2.0")
        api("dev.botta:kotlin-extensions:1.0.2")
        api("dev.botta:time:1.0.0")
        api("dev.botta:env:2.0.0")
        api("dev.botta:json:1.0.0")
        api("com.google.code.gson:gson:2.13.1")
        api("io.javalin:javalin:6.6.0")
        api("org.eclipse.jetty:jetty-client:11.0.25")
        api("org.slf4j:slf4j-api:2.0.17")
        api("org.apache.logging.log4j:log4j-core:2.24.3")
        api("org.apache.logging.log4j:log4j-slf4j2-impl:2.24.3")
        api("org.fusesource.jansi:jansi:2.4.2")
        api("com.zaxxer:HikariCP:6.3.0")
        api("org.jooq:jooq:3.20.3")
    }
}
