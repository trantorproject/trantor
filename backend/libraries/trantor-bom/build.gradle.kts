plugins {
    `java-platform`
}

javaPlatform {
    allowDependencies()
}

dependencies {
    api(platform("org.junit:junit-bom:5.11.0"))
    api(platform("org.assertj:assertj-bom:3.26.3"))

    constraints {
        api("io.mockk:mockk:1.13.12")
        api("io.rest-assured:rest-assured:5.5.0")
        api("dev.botta:cqbus:1.2.0")
        api("dev.botta:kotlin-extensions:1.0.2")
        api("dev.botta:time:1.0.0")
        api("dev.botta:env:2.0.0")
        api("dev.botta:json:1.0.0")
        api("com.google.code.gson:gson:2.11.0")
        api("io.javalin:javalin:6.2.0")
        api("org.eclipse.jetty:jetty-client:11.0.22")
        api("org.slf4j:slf4j-simple:2.0.16")
        api("com.zaxxer:HikariCP:5.1.0")
        api("org.jooq:jooq:3.19.11")
    }
}
