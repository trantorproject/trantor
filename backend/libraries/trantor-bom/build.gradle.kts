plugins {
    `java-platform`
}

javaPlatform {
    allowDependencies()
}

dependencies {
    api(platform("org.junit:junit-bom:5.10.2"))
    api(platform("org.assertj:assertj-bom:3.26.3"))

    constraints {
        api("io.mockk:mockk:1.13.10")
        api("io.rest-assured:rest-assured:5.4.0")
        api("dev.botta:cqbus:1.2.0")
        api("dev.botta:kotlin-extensions:1.0.2")
        api("dev.botta:time:1.0.0")
        api("dev.botta:env:2.0.0")
        api("dev.botta:json:1.0.0")
        api("com.google.code.gson:gson:2.10.1")
        api("io.javalin:javalin:6.1.3")
        api("org.eclipse.jetty:jetty-client:11.0.20")
        api("org.slf4j:slf4j-simple:2.0.13")
        api("com.zaxxer:HikariCP:5.1.0")
    }
}
