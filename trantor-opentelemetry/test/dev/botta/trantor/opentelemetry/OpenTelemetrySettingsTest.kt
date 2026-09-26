@file:Suppress("ClassName")

package dev.botta.trantor.opentelemetry

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class OpenTelemetrySettingsTest {
    @Nested
    inner class `a list of pairs from the environment` {
        @Test
        fun `is read as the spec writes it`() {
            val pairs = parseKeyValues("api-key=abc123,team=payments")

            assertThat(pairs).containsExactlyInAnyOrderEntriesOf(mapOf("api-key" to "abc123", "team" to "payments"))
        }

        @Test
        fun `has its values decoded`() {
            assertThat(parseKeyValues("Authorization=Basic%20dXNlcg%3D%3D"))
                .containsEntry("Authorization", "Basic dXNlcg==")
        }

        @Test
        fun `ignores spaces around the pairs and what is not a pair`() {
            assertThat(parseKeyValues(" team = payments , nonsense,,"))
                .containsExactlyEntriesOf(mapOf("team" to "payments"))
        }

        @Test
        fun `that is missing is empty`() {
            assertThat(parseKeyValues(null)).isEmpty()
            assertThat(parseKeyValues("")).isEmpty()
        }
    }

    @Nested
    inner class `the endpoint the spans and the metrics go to` {
        @Test
        fun `over HTTP is the one of each signal under the base`() {
            val settings = OpenTelemetrySettings(endpoint = "http://collector:4318/mycollector/")

            assertThat(OtlpExporters.endpointOf(settings, "traces"))
                .isEqualTo("http://collector:4318/mycollector/v1/traces")
            assertThat(OtlpExporters.endpointOf(settings, "metrics"))
                .isEqualTo("http://collector:4318/mycollector/v1/metrics")
        }

        @Test
        fun `over gRPC is the base itself`() {
            val settings = OpenTelemetrySettings(endpoint = "http://collector:4317", protocol = OtlpProtocols.GRPC)

            assertThat(OtlpExporters.endpointOf(settings, "metrics")).isEqualTo("http://collector:4317")
        }

        @Test
        fun `when nobody says, is the collector on this machine`() {
            val http = OpenTelemetrySettings(endpoint = null, protocol = OtlpProtocols.HTTP_PROTOBUF)
            val grpc = OpenTelemetrySettings(endpoint = null, protocol = OtlpProtocols.GRPC)

            assertThat(OtlpExporters.endpointOf(http, "traces")).isEqualTo("http://localhost:4318/v1/traces")
            assertThat(OtlpExporters.endpointOf(grpc, "traces")).isEqualTo("http://localhost:4317")
        }
    }

    @Test
    fun `a protocol the exporter does not speak fails, saying which ones it does`() {
        val settings = OpenTelemetrySettings(protocol = "http/json")

        assertThatThrownBy { OtlpExporters.spanExporter(settings) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("http/json")
            .hasMessageContaining("grpc")
            .hasMessageContaining("http/protobuf")
    }
}
