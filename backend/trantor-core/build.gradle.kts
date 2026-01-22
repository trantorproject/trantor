dependencies {
    api(project(":trantor-primitives"))
    api(project(":trantor-hosting"))
    api(project(":trantor-gson"))
    api("dev.botta:cqbus")
}

extra.set("POM_NAME", "Trantor Core")
extra.set("POM_DESCRIPTION", "Trantor framework core")
