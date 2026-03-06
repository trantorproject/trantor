dependencies {
    api(project(":trantor-core"))
    api("software.amazon.awssdk:auth")
}

extra.set("POM_NAME", "Trantor AWS")
extra.set("POM_DESCRIPTION", "Common AWS utilities")
