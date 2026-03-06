package dev.botta.trantor.aws

import dev.botta.trantor.di.ServiceRegistry
import software.amazon.awssdk.auth.credentials.*

fun ServiceRegistry.addAws() {
    if (has<AwsCredentialsProvider>()) return

    addSingleton<AwsCredentialsProvider> { StaticCredentialsProvider.create(
        AwsBasicCredentials.create(config.required("aws.accessKeyId"), config.required("aws.secretAccessKey")))
    }
}
