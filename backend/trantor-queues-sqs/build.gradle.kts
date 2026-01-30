dependencies {
    api(project(":trantor-core"))
    implementation(project(":trantor-aws"))
    implementation("software.amazon.awssdk:sqs")
}

extra.set("POM_NAME", "Trantor SQS Queue")
extra.set("POM_DESCRIPTION", "")
