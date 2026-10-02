@file:Suppress("ClassName")

package dev.botta.trantor.queues

import dev.botta.trantor.config.ConfigManager
import dev.botta.trantor.config.providers.addMemoryCollection
import dev.botta.trantor.serialization.gson.GsonSerializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider

class SqsQueueFactoryTest {
    @Nested
    inner class `the settings` {
        @Test
        fun `come from the section the queue is declared in`() {
            config.addMemoryCollection(
                "jobs.queues.emails.driver" to "sqs",
                "jobs.queues.emails.region" to "sa-east-1",
                "jobs.queues.emails.pollMaxMessages" to "5",
                "jobs.queues.emails.pollWaitTimeSeconds" to "10",
                "jobs.queues.emails.pollVisibilityTimeout" to "300",
            )

            val settings = queueOf("emails").settings

            assertThat(settings).isEqualTo(
                SqsQueueSettings(
                    region = "sa-east-1",
                    pollMaxMessages = 5,
                    pollWaitTimeSeconds = 10,
                    pollVisibilityTimeout = 300,
                ),
            )
        }

        @Test
        fun `are the defaults when the section only names the driver`() {
            config.addMemoryCollection("jobs.queues.emails.driver" to "sqs")

            assertThat(queueOf("emails").settings).isEqualTo(SqsQueueSettings())
        }
    }

    @Nested
    inner class `the region and the endpoint` {
        @Test
        fun `are those of aws when the queue does not say`() {
            config.addMemoryCollection(
                "aws.region" to "eu-west-1",
                "aws.endpointOverride" to "http://localhost:4566",
                "jobs.queues.emails.driver" to "sqs",
            )

            val settings = queueOf("emails").settings

            assertThat(settings.region).isEqualTo("eu-west-1")
            assertThat(settings.endpointOverride).isEqualTo("http://localhost:4566")
        }

        @Test
        fun `of the queue win over those of aws`() {
            config.addMemoryCollection(
                "aws.region" to "eu-west-1",
                "aws.endpointOverride" to "http://localhost:4566",
                "jobs.queues.emails.driver" to "sqs",
                "jobs.queues.emails.region" to "sa-east-1",
                "jobs.queues.emails.endpointOverride" to "http://localhost:9324",
            )

            val settings = queueOf("emails").settings

            assertThat(settings.region).isEqualTo("sa-east-1")
            assertThat(settings.endpointOverride).isEqualTo("http://localhost:9324")
        }
    }

    @Test
    fun `the queue is called what it was told, which is the name of the queue in SQS`() {
        config.addMemoryCollection("jobs.queues.emails.driver" to "sqs")

        assertThat(queueOf("app-emails-production").name).isEqualTo("app-emails-production")
    }

    private fun queueOf(name: String) =
        factory.createFromConfig(name, config.getSection("jobs.queues.emails")) as SqsQueue

    private val config = ConfigManager()
    private val credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("key", "secret"))
    private val factory = SqsQueueFactory(credentials, GsonSerializer(), config)
}
