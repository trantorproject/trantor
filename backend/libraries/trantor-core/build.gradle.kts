dependencies {
    api("dev.botta:kotlin-extensions")
    api("dev.botta:time")
    api("dev.botta:env")
    api("dev.botta:json")
    api("org.slf4j:slf4j-api")
    api("org.apache.logging.log4j:log4j-core")
    api("org.apache.logging.log4j:log4j-slf4j2-impl")
    api("com.github.f4b6a3:uuid-creator:6.1.1")
    runtimeOnly("org.fusesource.jansi:jansi")
}

extra.set("POM_NAME", "Trantor Core")
extra.set("POM_DESCRIPTION", "Core building blocks of Trantor framework")

tasks.register("generateBuildInfo") {
    val outputFile = layout.buildDirectory
        .file("generated/build-info/META-INF/trantor-build-info.properties")
        .get().asFile

    outputs.file(outputFile)

    doLast {
        val version = rootProject.file("VERSION").readText().trim()
        outputFile.parentFile.mkdirs()
        outputFile.writeText("build.version=$version")
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn("generateBuildInfo")

    from(layout.buildDirectory.dir("generated/build-info")) {
        include("META-INF/trantor-build-info.properties")
    }
}
