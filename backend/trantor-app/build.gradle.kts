dependencies {
    api("dev.botta:cqbus")
    api(project(":trantor-primitives"))
    api(project(":trantor-config"))
    api(project(":trantor-di"))
    api(project(":trantor-hosting"))
    api(project(":trantor-domain"))
    api(project(":trantor-gson"))
}

extra.set("POM_NAME", "Trantor App Services")
extra.set("POM_DESCRIPTION", "")
