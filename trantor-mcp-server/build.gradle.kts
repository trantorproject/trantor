dependencies {
    api(project(":trantor-ai"))
    api(project(":trantor-web"))
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
}

extra.set("POM_NAME", "Trantor MCP Server")
extra.set("POM_DESCRIPTION", "An MCP server on the routes of Trantor, whose tools are the use cases of the application")
