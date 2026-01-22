dependencies {
    api("dev.botta:cqbus")
    api(project(":core:trantor-core"))
    api(project(":core:trantor-config"))
    api(project(":core:trantor-di"))
    api(project(":core:trantor-hosting"))
    api(project(":libraries:trantor-domain"))
    api(project(":libraries:trantor-gson"))
}

extra.set("POM_NAME", "Trantor App Services")
extra.set("POM_DESCRIPTION", "")
