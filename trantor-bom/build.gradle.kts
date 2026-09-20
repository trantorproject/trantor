plugins {
    `java-platform`
}

javaPlatform {
    allowDependencies()
}

extra.set("POM_NAME", "Trantor BOM (Bill of Materials)")
extra.set("POM_DESCRIPTION", "This Bill of Materials POM can be used to ease dependency management when using Trantor Framework")
val version = rootProject.file("VERSION").readText().trim()

dependencies {
    api(platform("org.junit:junit-bom:6.0.2"))
    api(platform("org.assertj:assertj-bom:3.27.6"))
    api(platform("software.amazon.awssdk:bom:2.41.4"))
    api(platform("com.squareup.okhttp3:okhttp-bom:5.3.0"))

    constraints {
        api("io.mockk:mockk:1.14.7")
        api("io.rest-assured:rest-assured:6.0.0")
        api("dev.botta:cqbus:2.0.1")
        api("dev.botta:kotlin-extensions:1.0.2")
        api("dev.botta:time:1.0.0")
        api("dev.botta:env:2.0.0")
        api("dev.botta:json:1.0.0")
        api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
        api("org.jetbrains.kotlinx:kotlinx-schema-generator-json:0.5.0")
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
        api("com.github.ben-manes.caffeine:caffeine:3.2.3")
        api("com.github.kagkarlsson:db-scheduler:16.7.0")
        api("org.hibernate.validator:hibernate-validator:9.1.0.Final")
        api("jakarta.validation:jakarta.validation-api:3.1.1")

        api("dev.botta.trantor:trantor-ai:$version")
        api("dev.botta.trantor:trantor-aws:$version")
        api("dev.botta.trantor:trantor-config:$version")
        api("dev.botta.trantor:trantor-core:$version")
        api("dev.botta.trantor:trantor-data:$version")
        api("dev.botta.trantor:trantor-di:$version")
        api("dev.botta.trantor:trantor-domain:$version")
        api("dev.botta.trantor:trantor-gson:$version")
        api("dev.botta.trantor:trantor-hosting:$version")
        api("dev.botta.trantor:trantor-primitives:$version")
        api("dev.botta.trantor:trantor-queues-sqs:$version")
        api("dev.botta.trantor:trantor-taskpool:$version")
        api("dev.botta.trantor:trantor-test:$version")
        api("dev.botta.trantor:trantor-web:$version")
        api("dev.botta.trantor:trantor-web-client:$version")
    }
}
