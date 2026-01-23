dependencies {
    api(project(":trantor-primitives"))
    api(project(":trantor-di"))
    api(project(":trantor-hosting"))
}

extra.set("POM_NAME", "Trantor Taskpool")
extra.set("POM_DESCRIPTION", "Taskpool for running async tasks")
