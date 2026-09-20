@file:Suppress("ClassName")

package dev.botta.trantor.core.jobs.serialization

import dev.botta.trantor.core.jobs.Job
import dev.botta.trantor.core.jobs.JobType
import dev.botta.trantor.serialization.gson.GsonSerializer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DefaultJobSerializerTest {
    @Nested
    inner class `round trip` {
        @Test
        fun `a job comes back with its contents`() {
            serializer.register(SendEmail::class)
            val job = SendEmail("nico@example.com", "Hola")

            val back = serializer.deserialize(serializer.serialize(job)) as SendEmail

            assertThat(back.to).isEqualTo("nico@example.com")
            assertThat(back.subject).isEqualTo("Hola")
        }

        @Test
        fun `and with the same identity, so a retry is the same job`() {
            serializer.register(SendEmail::class)
            val job = SendEmail("nico@example.com", "Hola")

            val back = serializer.deserialize(serializer.serialize(job))

            assertThat(back.id).isEqualTo(job.id)
            assertThat(back).isEqualTo(job)
        }

        @Test
        fun `what is stored is the job type, not the class name`() {
            serializer.register(RebuildReport::class)

            assertThat(serializer.serialize(RebuildReport()).type).isEqualTo("reports.rebuild.v2")
        }
    }

    @Nested
    inner class `registering` {
        @Test
        fun `says whether a class is known`() {
            assertThat(serializer.isRegistered(SendEmail::class)).isFalse()

            serializer.register(SendEmail::class)

            assertThat(serializer.isRegistered(SendEmail::class)).isTrue()
        }

        @Test
        fun `the same class twice is not a problem`() {
            serializer.register(SendEmail::class)
            serializer.register(SendEmail::class)

            assertThat(serializer.isRegistered(SendEmail::class)).isTrue()
        }

        @Test
        fun `two classes with one name would silently deserialize wrong, so it stops`() {
            serializer.register(SendEmail::class)

            assertThatThrownBy { serializer.register(Marketing.SendEmail::class) }
                .isInstanceOf(JobTypeCollisionError::class.java)
                .hasMessageContaining("Use @JobType")
        }
    }

    @Nested
    inner class `a type nobody registered` {
        @Test
        fun `cannot be deserialized, and says which one it was`() {
            assertThatThrownBy { serializer.deserialize(SerializedJob("SendEmail", "{}")) }
                .isInstanceOf(JobClassNotFound::class.java)
                .hasMessageContaining("SendEmail")
        }
    }

    private class SendEmail(val to: String = "", val subject: String = ""): Job()

    @JobType("reports.rebuild.v2")
    private class RebuildReport: Job()

    /** Another class with the same simple name, which is what a collision looks like in practice. */
    private object Marketing {
        class SendEmail: Job()
    }

    private val serializer = DefaultJobSerializer(GsonSerializer())
}
