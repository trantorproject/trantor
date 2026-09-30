plugins {
    kotlin("plugin.serialization") version "2.3.10"
}

dependencies {
    api(project(":trantor-primitives"))
    api(project(":trantor-domain"))
    api(project(":trantor-web-client"))
    api(project(":trantor-di"))
    // The serializer of a run that was given none
    implementation(project(":trantor-gson"))
    // To end the processes of the MCP servers with the application
    implementation(project(":trantor-hosting"))
    api("org.jetbrains.kotlinx:kotlinx-serialization-json")
    api("org.jetbrains.kotlinx:kotlinx-schema-generator-json")
}

dependencies {
    testImplementation(project(":trantor-gson"))
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
}

extra.set("POM_NAME", "Trantor AI")
extra.set("POM_DESCRIPTION", "Unified access to language models, tools and agents for Trantor framework")
