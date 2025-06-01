dependencies {
    api("dev.botta:kotlin-extensions")
    api("dev.botta:time")
    api("dev.botta:env")
    api("dev.botta:json")
    api("org.slf4j:slf4j-api")
    api("org.apache.logging.log4j:log4j-core")
    api("org.apache.logging.log4j:log4j-slf4j2-impl")
    runtimeOnly("org.fusesource.jansi:jansi")
}

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
