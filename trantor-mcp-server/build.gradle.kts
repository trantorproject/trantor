plugins {
    // For the @Serializable args of the tools in the tests
    kotlin("plugin.serialization") version "2.3.10"
}

dependencies {
    api(project(":trantor-ai"))
    api(project(":trantor-web"))
}

extra.set("POM_NAME", "Trantor MCP Server")
extra.set("POM_DESCRIPTION", "An MCP server on the routes of Trantor, whose tools are the use cases of the application")
